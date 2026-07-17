package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.dto.ArticleAiSummaryMessage;
import com.ai.aicommunity.config.AiProperties;
import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.entity.MqOutboxMessage;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.BloomFilterUtil;
import com.ai.aicommunity.utils.RedisData;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ArticleService {

    private static final String ARTICLE_DETAIL_KEY = "article:detail:";
    private static final String ARTICLE_VERSION_KEY = "article:version:";
    private static final String ARTICLE_REFRESH_LOCK_KEY = "lock:article:refresh:";
    private static final String NULL_VALUE = "null";
    private static final String DELETED_VERSION = "deleted";
    private static final double REDIS_DOWN_DB_FALLBACK_QPS = 20.0;
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, Object> articleLocalCache;
    private final Executor cacheRebuildExecutor;
    private final MqOutboxService mqOutboxService;
    private final AiProperties aiProperties;
    private final CacheInvalidationTaskService cacheInvalidationTaskService;
    private final RateLimiter redisDownDbFallbackLimiter = RateLimiter.create(REDIS_DOWN_DB_FALLBACK_QPS);

    public ArticleService(ArticleMapper articleMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          Cache<String, Object> articleLocalCache,
                          @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor,
                          MqOutboxService mqOutboxService,
                          AiProperties aiProperties,
                          CacheInvalidationTaskService cacheInvalidationTaskService) {
        this.articleMapper = articleMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.articleLocalCache = articleLocalCache;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.mqOutboxService = mqOutboxService;
        this.aiProperties = aiProperties;
        this.cacheInvalidationTaskService = cacheInvalidationTaskService;
        this.cacheInvalidationTaskService.setArticleService(this);
    }

    @Transactional(rollbackFor = Exception.class)
    // 发布文章：写 MySQL，加布隆过滤器，顺手预热文章详情缓存，需要的话再发 AI 摘要任务。
    public void create(ArticleDTO dto) {
        Article article = new Article();
        article.setUserId(UserHolder.getUserId());
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);

        BloomFilterUtil.addArticleId(article.getId());
        markArticleVersion(article);
        rebuildCache(article.getId(), buildDetailKey(article.getId()));

        if (aiProperties.isEnabled()) {
            enqueueAiSummaryAfterCommit(article);
        }
    }

    // 分页看文章列表：普通查库，不走详情缓存那套逻辑。
    public Page<Article> page(Integer current, Integer size) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        return articleMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<Article>().orderByDesc(Article::getCreateTime)
        );
    }

    // 查文章详情：布隆过滤器 -> Caffeine -> Redis -> MySQL，命中旧缓存时会做版本校验和逻辑过期处理。
    public Article detail(Long id) {
        // 先用布隆过滤器挡一波明显不存在的文章，避免乱传 id 一直打到缓存和数据库。
        if (!BloomFilterUtil.mightExist(id)) {
            return null;
        }

        String key = buildDetailKey(id);

        // 第一层先看本机 Caffeine，命中后也不是无脑返回，后面会做版本校验。
        Object local = articleLocalCache.getIfPresent(key);
        if (local instanceof RedisData redisData) {
            return handleRedisData(id, key, redisData);
        }

        // 本机没有再看 Redis，Redis 里存的是带逻辑过期时间的 RedisData。
        String cache;
        try {
            cache = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis 整体不可用时，不能让所有 Caffeine miss 请求都打 MySQL。
            // 令牌桶只放少量请求回源并回填本机缓存，其他请求快速失败保护数据库。
            return queryDbWhenRedisDown(id, key);
        }
        if (cache != null) {
            if (NULL_VALUE.equals(cache)) {
                return null;
            }
            try {
                RedisData redisData = objectMapper.readValue(cache, RedisData.class);
                articleLocalCache.put(key, redisData);
                return handleRedisData(id, key, redisData);
            } catch (Exception ignored) {
            }
        }

        // 两级缓存都没有，才回源 MySQL，然后顺手把 Redis 和 Caffeine 都重建。
        return rebuildCache(id, key);
    }

    // 修改文章：先改 MySQL，再更新版本 key，并删除 Redis/Caffeine 详情缓存。
    public void update(Long id, ArticleDTO dto) {
        Article article = getOwnedArticle(id);
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.updateById(article);
        // 先把最新版本号写到 Redis，后面读 Caffeine 时能发现本机旧缓存。
        markArticleVersionSafely(article);
        // 再删详情缓存。删失败不会假装没事，会落补偿任务后面继续删。
        invalidateCacheWithCompensation(id);
    }

    // 删除文章：删 MySQL，标记删除版本，清掉详情缓存，再写短 TTL 空值挡无效访问。
    public void delete(Long id) {
        Article article = getOwnedArticle(id);
        articleMapper.deleteById(article.getId());
        markArticleDeletedVersionSafely(id);
        invalidateCacheWithCompensation(id);
        try {
            redisTemplate.opsForValue().set(buildDetailKey(id), NULL_VALUE, Duration.ofMinutes(5));
        } catch (Exception e) {
            cacheInvalidationTaskService.saveArticleDetailTask(id, e);
        }
    }

    // 处理从 Caffeine 或 Redis 拿到的缓存数据：先查版本，再判断逻辑过期，必要时异步重建。
    private Article handleRedisData(Long id, String key, RedisData redisData) {
        Article article = objectMapper.convertValue(redisData.getData(), Article.class);
        if (article == null) {
            return null;
        }
        // Caffeine 命中了也要看 Redis 版本 key，不然多实例时本机可能一直拿旧文章。
        if (isStaleCachedArticle(article)) {
            invalidateCacheWithCompensation(id);
            return rebuildCache(id, key);
        }

        // 逻辑没过期就直接返回；过期了也先返回旧值，后台异步重建。
        if (redisData.getExpireTime() != null && redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return article;
        }

        String lockKey = ARTICLE_REFRESH_LOCK_KEY + id;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked;
        try {
            locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));
        } catch (Exception e) {
            // Redis 挂了就拿不到分布式重建锁，这时直接返回旧值，避免读接口被锁逻辑拖垮。
            return article;
        }

        if (Boolean.TRUE.equals(locked)) {
            // 只有抢到锁的线程去重建，其他请求直接返回旧值，避免一起打 MySQL。
            cacheRebuildExecutor.execute(() -> {
                try {
                    rebuildCache(id, key);
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            });
        }

        return article;
    }

    // Redis 宕机兜底：只允许少量 Caffeine miss 请求回源 MySQL，并且只回填本机缓存。
    private Article queryDbWhenRedisDown(Long id, String key) {
        if (!redisDownDbFallbackLimiter.tryAcquire()) {
            throw new BusinessException("文章服务繁忙，请稍后重试");
        }

        Article article = articleMapper.selectById(id);
        if (article == null) {
            return null;
        }

        RedisData redisData = new RedisData();
        redisData.setData(article);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(5));
        articleLocalCache.put(key, redisData);
        return article;
    }

    // 真正重建文章详情缓存：查 MySQL，写 RedisData，同时回填 Redis 和 Caffeine。
    private Article rebuildCache(Long id, String key) {
        try {
            Article article = articleMapper.selectById(id);
            if (article == null) {
                // 数据库也没有，写一个短 TTL 空值，挡住布隆过滤器误判后的重复查询。
                redisTemplate.opsForValue().set(key, NULL_VALUE, Duration.ofMinutes(5));
                articleLocalCache.invalidate(key);
                return null;
            }
            // 写缓存前再比一次版本，防止异步重建把旧文章又写回 Redis/Caffeine。
            if (!isCurrentArticleVersion(article)) {
                articleLocalCache.invalidate(key);
                return article;
            }

            RedisData redisData = new RedisData();
            redisData.setData(article);
            redisData.setExpireTime(LocalDateTime.now().plusMinutes(30));

            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(redisData),
                        Duration.ofMinutes(40 + ThreadLocalRandom.current().nextInt(10))
            );

            // 重建时 Redis 和本地缓存一起回填，下次同机器访问就不用再过 Redis。
            articleLocalCache.put(key, redisData);
            return article;
        } catch (Exception e) {
            return null;
        }
    }

    // 查文章并校验是不是当前登录用户自己的文章，用在修改和删除。
    private Article getOwnedArticle(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        Long userId = UserHolder.getUserId();
        if (userId == null || !userId.equals(article.getUserId())) {
            throw new BusinessException("只能操作自己的文章");
        }
        return article;
    }

    // 只做实际删除缓存动作：删本机 Caffeine 和 Redis 详情 key，不负责失败补偿。
    private void invalidateCacheOnly(Long id) {
        String key = buildDetailKey(id);
        articleLocalCache.invalidate(key);
        redisTemplate.delete(key);
    }

    // 给补偿任务用的入口：后台重试时直接调用这个方法删详情缓存。
    public void invalidateArticleDetailCacheNow(Long id) {
        invalidateCacheOnly(id);
    }

    // 文章点赞、评论、AI 摘要变化后调用：刷新版本并删除详情缓存，避免详情页继续显示旧数字。
    public void invalidateDetailCache(Long id) {
        refreshArticleVersionSafely(id);
        invalidateCacheWithCompensation(id);
    }

    // 文章创建成功后再发 AI 摘要消息：Outbox 先落库，事务提交后再发 RocketMQ。
    private void enqueueAiSummaryAfterCommit(Article article) {
        ArticleAiSummaryMessage payload = new ArticleAiSummaryMessage(
                article.getId(), article.getTitle(), article.getContent());
        try {
            MqOutboxMessage outboxMessage = mqOutboxService.savePending(
                    RocketMQConfig.ARTICLE_AI_SUMMARY_TOPIC,
                    objectMapper.writeValueAsString(payload));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mqOutboxService.sendNow(outboxMessage, payload);
                }
            });
        } catch (Exception e) {
            throw new BusinessException("创建AI摘要任务失败");
        }
    }

    // 释放缓存重建锁：必须 UUID 对上才删，避免删到别人的锁。
    private void releaseLock(String lockKey, String lockValue) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
    }

    // 拼文章详情缓存 key。
    private String buildDetailKey(Long id) {
        return ARTICLE_DETAIL_KEY + id;
    }

    // 拼文章版本 key，用来判断 Caffeine/Redis 里的文章是不是旧的。
    private String buildVersionKey(Long id) {
        return ARTICLE_VERSION_KEY + id;
    }

    // 从 MySQL 重新读取文章 updateTime，并写到 Redis 版本 key。
    private void refreshArticleVersion(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            markArticleDeletedVersion(id);
            return;
        }
        markArticleVersion(article);
    }

    // 安全刷新版本：Redis 出问题时不直接中断主流程，改成记录缓存失效补偿任务。
    private void refreshArticleVersionSafely(Long id) {
        try {
            refreshArticleVersion(id);
        } catch (Exception e) {
            cacheInvalidationTaskService.saveArticleDetailTask(id, e);
        }
    }

    // 写文章当前版本：失败就落补偿任务，后面继续删缓存。
    private void markArticleVersionSafely(Article article) {
        try {
            markArticleVersion(article);
        } catch (Exception e) {
            cacheInvalidationTaskService.saveArticleDetailTask(article.getId(), e);
        }
    }

    // 写文章删除版本：告诉读缓存的人这篇文章已经不能继续信旧值。
    private void markArticleDeletedVersionSafely(Long id) {
        try {
            markArticleDeletedVersion(id);
        } catch (Exception e) {
            cacheInvalidationTaskService.saveArticleDetailTask(id, e);
        }
    }

    // 删除缓存带兜底：先删一次，失败快速重试，再失败就写补偿表等定时任务处理。
    private void invalidateCacheWithCompensation(Long id) {
        try {
            invalidateCacheOnly(id);
        } catch (Exception firstFailure) {
            try {
                // 删除缓存偶发失败时先快速重试一次，不要马上把问题丢给后台。
                invalidateCacheOnly(id);
            } catch (Exception secondFailure) {
                // 还是失败就记到补偿表，定时任务后面继续删，避免缓存一直旧着。
                cacheInvalidationTaskService.saveArticleDetailTask(id, secondFailure);
            }
        }
    }

    // 把文章 updateTime 写成 Redis 版本标记。
    private void markArticleVersion(Article article) {
        redisTemplate.opsForValue().set(buildVersionKey(article.getId()), articleVersion(article), Duration.ofDays(1));
    }

    // 文章删除后写一个特殊版本，防止旧缓存继续被当成正常文章。
    private void markArticleDeletedVersion(Long id) {
        redisTemplate.opsForValue().set(buildVersionKey(id), DELETED_VERSION, Duration.ofDays(1));
    }

    // 异步重建写缓存前用：版本一致才允许写，防止旧数据回种。
    private boolean isCurrentArticleVersion(Article article) {
        String currentVersion = redisTemplate.opsForValue().get(buildVersionKey(article.getId()));
        return currentVersion == null || articleVersion(article).equals(currentVersion);
    }

    // 读 Caffeine 命中后用：发现本机文章版本落后，就别直接返回旧缓存。
    private boolean isStaleCachedArticle(Article article) {
        try {
            String currentVersion = redisTemplate.opsForValue().get(buildVersionKey(article.getId()));
            return currentVersion != null && !articleVersion(article).equals(currentVersion);
        } catch (Exception ignored) {
            return false;
        }
    }

    // 当前用 updateTime 当文章版本号，字段有变化就能反映出来。
    private String articleVersion(Article article) {
        return article.getUpdateTime() == null ? "" : article.getUpdateTime().toString();
    }
}
