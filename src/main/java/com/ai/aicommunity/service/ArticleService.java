package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.BloomFilterUtil;
import com.ai.aicommunity.utils.RedisData;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, Object> articleLocalCache;

    private final ExecutorService cacheExecutor = Executors.newFixedThreadPool(5);

    public ArticleService(ArticleMapper articleMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          Cache<String, Object> articleLocalCache) {
        this.articleMapper = articleMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.articleLocalCache = articleLocalCache;
    }

    public Article detail(Long id) {
        if (!BloomFilterUtil.mightExist(id)) {
            return null;
        }

        String key = "article:detail:" + id;

        Object local = articleLocalCache.getIfPresent(key);
        if (local != null) {
            return (Article) local;
        }

        String cache = redisTemplate.opsForValue().get(key);
        if (cache != null) {
            try {
                RedisData redisData = objectMapper.readValue(cache, RedisData.class);
                Article article = objectMapper.convertValue(redisData.getData(), Article.class);

                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    articleLocalCache.put(key, article);
                    return article;
                }

                String lockKey = "lock:article:refresh:" + id;
                Boolean lock = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(10));

                if (Boolean.TRUE.equals(lock)) {
                    cacheExecutor.submit(() -> rebuildCache(id, key));
                }

                return article;
            } catch (Exception ignored) {
            }
        }

        return rebuildCache(id, key);
    }

    private Article rebuildCache(Long id, String key) {
        try {
            Article article = articleMapper.selectById(id);
            if (article == null) {
                redisTemplate.opsForValue().set(key, "null", Duration.ofMinutes(5));
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

            articleLocalCache.put(key, article);
            return article;
        } catch (Exception e) {
            return null;
        }
    }
}
