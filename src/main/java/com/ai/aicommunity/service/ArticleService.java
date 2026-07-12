package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.BloomFilterUtil;
import com.ai.aicommunity.utils.RedisData;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ArticleService {

    private static final String ARTICLE_DETAIL_KEY = "article:detail:";
    private static final String ARTICLE_REFRESH_LOCK_KEY = "lock:article:refresh:";
    private static final String NULL_VALUE = "null";
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

    public ArticleService(ArticleMapper articleMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          Cache<String, Object> articleLocalCache,
                          @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor) {
        this.articleMapper = articleMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.articleLocalCache = articleLocalCache;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
    }

    public void create(ArticleDTO dto) {
        Article article = new Article();
        article.setUserId(UserHolder.getUserId());
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);

        BloomFilterUtil.addArticleId(article.getId());
        rebuildCache(article.getId(), buildDetailKey(article.getId()));
    }

    public Page<Article> page(Integer current, Integer size) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        return articleMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<Article>().orderByDesc(Article::getCreateTime)
        );
    }

    public Article detail(Long id) {
        if (!BloomFilterUtil.mightExist(id)) {
            return null;
        }

        String key = buildDetailKey(id);

        Object local = articleLocalCache.getIfPresent(key);
        if (local instanceof RedisData redisData) {
            return handleRedisData(id, key, redisData);
        }

        String cache = redisTemplate.opsForValue().get(key);
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

        return rebuildCache(id, key);
    }

    public void update(Long id, ArticleDTO dto) {
        Article article = getOwnedArticle(id);
        article.setTitle(dto.getTitle());
        article.setContent(dto.getContent());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.updateById(article);
        invalidateCache(id);
    }

    public void delete(Long id) {
        Article article = getOwnedArticle(id);
        articleMapper.deleteById(article.getId());
        invalidateCache(id);
        redisTemplate.opsForValue().set(buildDetailKey(id), NULL_VALUE, Duration.ofMinutes(5));
    }

    private Article handleRedisData(Long id, String key, RedisData redisData) {
        Article article = objectMapper.convertValue(redisData.getData(), Article.class);
        if (article == null) {
            return null;
        }

        if (redisData.getExpireTime() != null && redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return article;
        }

        String lockKey = ARTICLE_REFRESH_LOCK_KEY + id;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));

        if (Boolean.TRUE.equals(locked)) {
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

    private Article rebuildCache(Long id, String key) {
        try {
            Article article = articleMapper.selectById(id);
            if (article == null) {
                redisTemplate.opsForValue().set(key, NULL_VALUE, Duration.ofMinutes(5));
                articleLocalCache.invalidate(key);
                return null;
            }

            RedisData redisData = new RedisData();
            redisData.setData(article);
            redisData.setExpireTime(LocalDateTime.now().plusMinutes(30));

            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(redisData),
                        Duration.ofMinutes(40 + ThreadLocalRandom.current().nextInt(10))
            );

            articleLocalCache.put(key, redisData);
            return article;
        } catch (Exception e) {
            return null;
        }
    }

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

    private void invalidateCache(Long id) {
        String key = buildDetailKey(id);
        articleLocalCache.invalidate(key);
        redisTemplate.delete(key);
    }

    private void releaseLock(String lockKey, String lockValue) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
    }

    private String buildDetailKey(Long id) {
        return ARTICLE_DETAIL_KEY + id;
    }
}
