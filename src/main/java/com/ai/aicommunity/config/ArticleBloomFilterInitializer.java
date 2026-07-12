package com.ai.aicommunity.config;

import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.utils.BloomFilterUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ArticleBloomFilterInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ArticleBloomFilterInitializer.class);

    private final ArticleMapper articleMapper;

    public ArticleBloomFilterInitializer(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Article> articles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>().select(Article::getId)
            );
            for (Article article : articles) {
                BloomFilterUtil.addArticleId(article.getId());
            }
            log.info("Initialized article bloom filter with {} article ids", articles.size());
        } catch (Exception e) {
            log.warn("Failed to initialize article bloom filter: {}", e.getMessage());
        }
    }
}
