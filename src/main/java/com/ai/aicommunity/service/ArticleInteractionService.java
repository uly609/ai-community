package com.ai.aicommunity.service;

import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.entity.ArticleFavorite;
import com.ai.aicommunity.entity.ArticleLike;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.ArticleFavoriteMapper;
import com.ai.aicommunity.mapper.ArticleLikeMapper;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArticleInteractionService {

    private static final String ARTICLE_LIKED_USERS_KEY = "article:like:users:";
    private static final String ARTICLE_FAVORITED_USERS_KEY = "article:favorite:users:";
    private static final String ARTICLE_HOT_RANK_KEY = "article:hot:rank";

    private final ArticleMapper articleMapper;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFavoriteMapper articleFavoriteMapper;
    private final StringRedisTemplate redisTemplate;
    private final ArticleService articleService;

    public ArticleInteractionService(ArticleMapper articleMapper,
                                     ArticleLikeMapper articleLikeMapper,
                                     ArticleFavoriteMapper articleFavoriteMapper,
                                     StringRedisTemplate redisTemplate,
                                     ArticleService articleService) {
        this.articleMapper = articleMapper;
        this.articleLikeMapper = articleLikeMapper;
        this.articleFavoriteMapper = articleFavoriteMapper;
        this.redisTemplate = redisTemplate;
        this.articleService = articleService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void like(Long articleId) {
        Long userId = requireUserId();
        ensureArticleExists(articleId);
        if (existsLike(articleId, userId)) {
            return;
        }

        ArticleLike like = new ArticleLike();
        like.setArticleId(articleId);
        like.setUserId(userId);
        like.setCreateTime(LocalDateTime.now());
        try {
            articleLikeMapper.insert(like);
        } catch (DuplicateKeyException ignored) {
            return;
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .setSql("like_count = like_count + 1")
                .set(Article::getUpdateTime, LocalDateTime.now()));

        runAfterCommit(() -> {
            redisTemplate.opsForSet().add(likedUsersKey(articleId), userId.toString());
            redisTemplate.opsForZSet().incrementScore(ARTICLE_HOT_RANK_KEY, articleId.toString(), 1D);
            articleService.invalidateDetailCache(articleId);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlike(Long articleId) {
        Long userId = requireUserId();
        int deleted = articleLikeMapper.delete(new LambdaQueryWrapper<ArticleLike>()
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId));
        if (deleted == 0) {
            return;
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .gt(Article::getLikeCount, 0)
                .setSql("like_count = like_count - 1")
                .set(Article::getUpdateTime, LocalDateTime.now()));

        runAfterCommit(() -> {
            redisTemplate.opsForSet().remove(likedUsersKey(articleId), userId.toString());
            Double score = redisTemplate.opsForZSet()
                    .incrementScore(ARTICLE_HOT_RANK_KEY, articleId.toString(), -1D);
            if (score != null && score <= 0) {
                redisTemplate.opsForZSet().remove(ARTICLE_HOT_RANK_KEY, articleId.toString());
            }
            articleService.invalidateDetailCache(articleId);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void favorite(Long articleId) {
        Long userId = requireUserId();
        ensureArticleExists(articleId);
        if (existsFavorite(articleId, userId)) {
            return;
        }

        ArticleFavorite favorite = new ArticleFavorite();
        favorite.setArticleId(articleId);
        favorite.setUserId(userId);
        favorite.setCreateTime(LocalDateTime.now());
        try {
            articleFavoriteMapper.insert(favorite);
        } catch (DuplicateKeyException ignored) {
            return;
        }

        runAfterCommit(() -> redisTemplate.opsForSet()
                .add(favoritedUsersKey(articleId), userId.toString()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void unfavorite(Long articleId) {
        Long userId = requireUserId();
        int deleted = articleFavoriteMapper.delete(new LambdaQueryWrapper<ArticleFavorite>()
                .eq(ArticleFavorite::getArticleId, articleId)
                .eq(ArticleFavorite::getUserId, userId));
        if (deleted == 0) {
            return;
        }
        runAfterCommit(() -> redisTemplate.opsForSet()
                .remove(favoritedUsersKey(articleId), userId.toString()));
    }

    public List<Article> hotArticles(Integer limit) {
        int size = limit == null || limit < 1 ? 10 : Math.min(limit, 20);
        Set<String> cachedIds = redisTemplate.opsForZSet().reverseRange(ARTICLE_HOT_RANK_KEY, 0, size - 1L);
        if (cachedIds == null || cachedIds.isEmpty()) {
            List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                    .orderByDesc(Article::getLikeCount)
                    .orderByDesc(Article::getCreateTime)
                    .last("LIMIT " + size));
            for (Article article : articles) {
                if (article.getLikeCount() != null && article.getLikeCount() > 0) {
                    redisTemplate.opsForZSet().add(ARTICLE_HOT_RANK_KEY,
                            article.getId().toString(), article.getLikeCount());
                }
            }
            return articles;
        }

        List<Long> ids = cachedIds.stream().map(Long::valueOf).toList();
        Map<Long, Article> articleMap = new HashMap<>();
        for (Article article : articleMapper.selectBatchIds(ids)) {
            articleMap.put(article.getId(), article);
        }
        List<Article> orderedArticles = new ArrayList<>();
        for (Long id : ids) {
            Article article = articleMap.get(id);
            if (article != null) {
                orderedArticles.add(article);
            }
        }
        return orderedArticles;
    }

    private boolean existsLike(Long articleId, Long userId) {
        return articleLikeMapper.selectCount(new LambdaQueryWrapper<ArticleLike>()
                .eq(ArticleLike::getArticleId, articleId)
                .eq(ArticleLike::getUserId, userId)) > 0;
    }

    private boolean existsFavorite(Long articleId, Long userId) {
        return articleFavoriteMapper.selectCount(new LambdaQueryWrapper<ArticleFavorite>()
                .eq(ArticleFavorite::getArticleId, articleId)
                .eq(ArticleFavorite::getUserId, userId)) > 0;
    }

    private void ensureArticleExists(Long articleId) {
        if (articleMapper.selectById(articleId) == null) {
            throw new BusinessException("文章不存在");
        }
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String likedUsersKey(Long articleId) {
        return ARTICLE_LIKED_USERS_KEY + articleId;
    }

    private String favoritedUsersKey(Long articleId) {
        return ARTICLE_FAVORITED_USERS_KEY + articleId;
    }
}
