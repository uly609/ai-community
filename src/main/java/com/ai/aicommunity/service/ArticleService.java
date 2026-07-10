package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, Object> articleLocalCache;

    private final Object cacheLock = new Object();

    public ArticleService(ArticleMapper articleMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          Cache<String, Object> articleLocalCache) {
        this.articleMapper = articleMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.articleLocalCache = articleLocalCache;
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
    }

    public Page<Article> page(Integer current, Integer size) {
        return articleMapper.selectPage(new Page<>(current, size), null);
    }

    public Article detail(Long id) {
        String key = "article:detail:" + id;

        // 第一层：查询Caffeine本地缓存
        Object localArticle = articleLocalCache.getIfPresent(key);
        if (localArticle != null) {
            return (Article) localArticle;
        }

        // 第二层：查询Redis
        String cache = redisTemplate.opsForValue().get(key);
        if (cache != null) {
            if ("null".equals(cache)) {
                return null;
            }
            try {
                Article article = objectMapper.readValue(cache, Article.class);
                articleLocalCache.put(key, article);
                return article;
            } catch (JsonProcessingException ignored) {
            }
        }

        synchronized (cacheLock) {
            cache = redisTemplate.opsForValue().get(key);
            if (cache != null) {
                if ("null".equals(cache)) {
                    return null;
                }
                try {
                    Article article = objectMapper.readValue(cache, Article.class);
                    articleLocalCache.put(key, article);
                    return article;
                } catch (JsonProcessingException ignored) {
                }
            }

            Article article = articleMapper.selectById(id);

            if (article == null) {
                redisTemplate.opsForValue().set(
                        key,
                        "null",
                        Duration.ofMinutes(5)
                );
                return null;
            }

            try {
                redisTemplate.opsForValue().set(
                        key,
                        objectMapper.writeValueAsString(article),
                        Duration.ofMinutes(30)
                );
                articleLocalCache.put(key, article);
            } catch (JsonProcessingException ignored) {
            }

            return article;
        }
    }
}
