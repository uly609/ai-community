package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ArticleService(ArticleMapper articleMapper,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.articleMapper = articleMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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

        String cache = redisTemplate.opsForValue().get(key);

        // Redis中存在缓存
        if (cache != null) {
            // 缓存空值，直接返回，避免缓存穿透
            if ("null".equals(cache)) {
                return null;
            }

            try {
                return objectMapper.readValue(cache, Article.class);
            } catch (JsonProcessingException ignored) {
            }
        }

        Article article = articleMapper.selectById(id);

        // 数据库不存在，缓存空值
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
        } catch (JsonProcessingException ignored) {
        }

        return article;
    }
}
