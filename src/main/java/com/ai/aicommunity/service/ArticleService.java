package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArticleService {

    private final ArticleMapper articleMapper;

    public ArticleService(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
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

    public List<Article> list() {
        return articleMapper.selectList(null);
    }

    public Article detail(Long id) {
        return articleMapper.selectById(id);
    }
}
