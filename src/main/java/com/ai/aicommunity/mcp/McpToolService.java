package com.ai.aicommunity.mcp;

import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.mapper.TrainingCampMapper;
import com.ai.aicommunity.service.ArticleInteractionService;
import com.ai.aicommunity.service.ArticleService;
import com.ai.aicommunity.service.TrainingCampService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class McpToolService {

    private static final int MAX_PAGE_SIZE = 20;

    private final ArticleMapper articleMapper;
    private final ArticleService articleService;
    private final ArticleInteractionService articleInteractionService;
    private final TrainingCampMapper trainingCampMapper;
    private final TrainingCampService trainingCampService;

    public McpToolService(ArticleMapper articleMapper,
                          ArticleService articleService,
                          ArticleInteractionService articleInteractionService,
                          TrainingCampMapper trainingCampMapper,
                          TrainingCampService trainingCampService) {
        this.articleMapper = articleMapper;
        this.articleService = articleService;
        this.articleInteractionService = articleInteractionService;
        this.trainingCampMapper = trainingCampMapper;
        this.trainingCampService = trainingCampService;
    }

    public Object call(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "search_articles" -> searchArticles(arguments);
            case "get_article_detail" -> getArticleDetail(arguments);
            case "list_hot_articles" -> listHotArticles(arguments);
            case "list_training_camps" -> listTrainingCamps(arguments);
            case "get_training_camp" -> getTrainingCamp(arguments);
            default -> throw new IllegalArgumentException("未知 MCP 工具: " + name);
        };
    }

    private Page<Article> searchArticles(Map<String, Object> arguments) {
        String keyword = stringArg(arguments, "keyword", "");
        int current = intArg(arguments, "current", 1);
        int size = pageSize(arguments);

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .orderByDesc(Article::getCreateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Article::getTitle, keyword)
                    .or()
                    .like(Article::getContent, keyword);
        }
        return articleMapper.selectPage(new Page<>(current, size), wrapper);
    }

    private Article getArticleDetail(Map<String, Object> arguments) {
        Long articleId = longArg(arguments, "articleId");
        return articleService.detail(articleId);
    }

    private List<Article> listHotArticles(Map<String, Object> arguments) {
        int limit = Math.min(intArg(arguments, "limit", 10), MAX_PAGE_SIZE);
        return articleInteractionService.hotArticles(limit);
    }

    private Page<TrainingCamp> listTrainingCamps(Map<String, Object> arguments) {
        int current = intArg(arguments, "current", 1);
        int size = pageSize(arguments);
        return trainingCampService.page(current, size);
    }

    private TrainingCamp getTrainingCamp(Map<String, Object> arguments) {
        Long campId = longArg(arguments, "campId");
        return trainingCampMapper.selectById(campId);
    }

    private int pageSize(Map<String, Object> arguments) {
        return Math.min(intArg(arguments, "size", 10), MAX_PAGE_SIZE);
    }

    private String stringArg(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int intArg(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Long longArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
