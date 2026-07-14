package com.ai.aicommunity.service;

import com.ai.aicommunity.config.AiProperties;
import com.ai.aicommunity.dto.ArticleAiSummaryMessage;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ArticleAiSummaryService {

    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final AiProperties aiProperties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final ArticleMapper articleMapper;
    private final ArticleService articleService;

    public ArticleAiSummaryService(AiProperties aiProperties,
                                   RestClient.Builder restClientBuilder,
                                   ObjectMapper objectMapper,
                                   ArticleMapper articleMapper,
                                   ArticleService articleService) {
        this.aiProperties = aiProperties;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.articleMapper = articleMapper;
        this.articleService = articleService;
    }

    public void generateAndSave(ArticleAiSummaryMessage message) {
        if (!aiProperties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(aiProperties.getApiKey())) {
            throw new IllegalStateException("已开启AI摘要，但未配置 app.ai.api-key");
        }

        String summary = requestSummary(message);
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, message.getArticleId())
                .set(Article::getAiSummary, summary)
                .set(Article::getUpdateTime, LocalDateTime.now()));
        articleService.invalidateDetailCache(message.getArticleId());
    }

    private String requestSummary(ArticleAiSummaryMessage message) {
        Map<String, Object> request = Map.of(
                "model", aiProperties.getModel(),
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是中文技术社区助手。只输出100字以内的文章摘要，不要标题，不要Markdown。"),
                        Map.of("role", "user", "content",
                                "文章标题：" + message.getTitle() + "\n文章正文：" + message.getContent())
                )
        );
        try {
            String response = restClientBuilder.build().post()
                    .uri(normalizeChatCompletionsUrl())
                    .headers(headers -> headers.setBearerAuth(aiProperties.getApiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            JsonNode choices = objectMapper.readTree(response).path("choices");
            String summary = choices.path(0).path("message").path("content").asText();
            if (!StringUtils.hasText(summary)) {
                throw new IllegalStateException("AI服务未返回摘要内容");
            }
            return summary.length() > MAX_SUMMARY_LENGTH
                    ? summary.substring(0, MAX_SUMMARY_LENGTH) : summary;
        } catch (Exception e) {
            throw new IllegalStateException("AI摘要生成失败", e);
        }
    }

    private String normalizeChatCompletionsUrl() {
        String baseUrl = aiProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }
}
