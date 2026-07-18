package com.ai.aicommunity.service;

import com.ai.aicommunity.config.AiProperties;
import com.ai.aicommunity.dto.LearningAssistantResponse;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.mapper.TrainingCampMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class LearningAssistantService {

    private static final int ARTICLE_LIMIT = 3;
    private static final int CAMP_LIMIT = 2;
    private static final int MAX_ARTICLE_CONTENT_LENGTH = 600;
    private static final int MAX_AI_ANSWER_LENGTH = 2000;

    private final ArticleMapper articleMapper;
    private final TrainingCampMapper trainingCampMapper;
    private final AiProperties aiProperties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public LearningAssistantService(ArticleMapper articleMapper,
                                    TrainingCampMapper trainingCampMapper,
                                    AiProperties aiProperties,
                                    RestClient.Builder restClientBuilder,
                                    ObjectMapper objectMapper) {
        this.articleMapper = articleMapper;
        this.trainingCampMapper = trainingCampMapper;
        this.aiProperties = aiProperties;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    // 学习问答助手：先从项目内容里找上下文，再让大模型基于这些上下文回答。
    public LearningAssistantResponse ask(String question) {
        List<Article> articles = searchArticles(question);
        List<TrainingCamp> camps = searchTrainingCamps(question);
        List<LearningAssistantResponse.Reference> references = buildReferences(articles, camps);

        if (references.isEmpty()) {
            return new LearningAssistantResponse(
                    "暂时没有在项目知识库里找到相关内容，你可以换个关键词再问。",
                    aiEnabled(),
                    references
            );
        }

        if (!aiEnabled()) {
            return new LearningAssistantResponse(buildFallbackAnswer(question, references), false, references);
        }

        return new LearningAssistantResponse(requestAnswer(question, articles, camps), true, references);
    }

    private List<Article> searchArticles(String question) {
        List<String> keywords = extractKeywords(question);
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>();
        wrapper.and(query -> {
            for (String keyword : keywords) {
                query.or(condition -> condition.like(Article::getTitle, keyword)
                        .or()
                        .like(Article::getContent, keyword));
            }
        }).orderByDesc(Article::getUpdateTime);
        return articleMapper.selectPage(new Page<>(1, ARTICLE_LIMIT), wrapper).getRecords();
    }

    private List<TrainingCamp> searchTrainingCamps(String question) {
        List<String> keywords = extractKeywords(question);
        LambdaQueryWrapper<TrainingCamp> wrapper = new LambdaQueryWrapper<TrainingCamp>();
        wrapper.and(query -> {
            for (String keyword : keywords) {
                query.or(condition -> condition.like(TrainingCamp::getTitle, keyword)
                        .or()
                        .like(TrainingCamp::getDescription, keyword));
            }
        }).orderByDesc(TrainingCamp::getUpdateTime);
        return trainingCampMapper.selectPage(new Page<>(1, CAMP_LIMIT), wrapper).getRecords();
    }

    private List<LearningAssistantResponse.Reference> buildReferences(List<Article> articles,
                                                                      List<TrainingCamp> camps) {
        List<LearningAssistantResponse.Reference> references = new ArrayList<>();
        for (Article article : articles) {
            references.add(new LearningAssistantResponse.Reference("article", article.getId(), article.getTitle()));
        }
        for (TrainingCamp camp : camps) {
            references.add(new LearningAssistantResponse.Reference("training_camp", camp.getId(), camp.getTitle()));
        }
        return references;
    }

    private String buildFallbackAnswer(String question, List<LearningAssistantResponse.Reference> references) {
        StringBuilder builder = new StringBuilder();
        builder.append("AI问答未开启，但已从项目内容中检索到相关资料。问题：")
                .append(question)
                .append("。你可以先查看：");
        for (LearningAssistantResponse.Reference reference : references) {
            builder.append("[").append(reference.getType()).append("]")
                    .append(reference.getTitle())
                    .append("(ID=").append(reference.getId()).append(")；");
        }
        return builder.toString();
    }

    private String requestAnswer(String question, List<Article> articles, List<TrainingCamp> camps) {
        Map<String, Object> request = Map.of(
                "model", aiProperties.getModel(),
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "你是AI知识社区的学习问答助手。只能基于给定的文章和训练营资料回答；资料不足时直接说明不知道，不要编造。回答使用中文，尽量简洁。"),
                        Map.of("role", "user", "content",
                                "用户问题：" + question + "\n\n项目资料：\n" + buildContext(articles, camps))
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
            String answer = choices.path(0).path("message").path("content").asText();
            if (!StringUtils.hasText(answer)) {
                throw new IllegalStateException("AI服务未返回问答内容");
            }
            return answer.length() > MAX_AI_ANSWER_LENGTH
                    ? answer.substring(0, MAX_AI_ANSWER_LENGTH) : answer;
        } catch (Exception e) {
            throw new IllegalStateException("学习问答生成失败", e);
        }
    }

    private String buildContext(List<Article> articles, List<TrainingCamp> camps) {
        StringBuilder builder = new StringBuilder();
        for (Article article : articles) {
            builder.append("文章ID：").append(article.getId())
                    .append("\n标题：").append(article.getTitle())
                    .append("\n摘要：").append(nullToEmpty(article.getAiSummary()))
                    .append("\n正文：").append(truncate(article.getContent(), MAX_ARTICLE_CONTENT_LENGTH))
                    .append("\n\n");
        }
        for (TrainingCamp camp : camps) {
            builder.append("训练营ID：").append(camp.getId())
                    .append("\n标题：").append(camp.getTitle())
                    .append("\n介绍：").append(nullToEmpty(camp.getDescription()))
                    .append("\n库存：").append(camp.getStock())
                    .append("\n开始时间：").append(camp.getStartTime())
                    .append("\n结束时间：").append(camp.getEndTime())
                    .append("\n\n");
        }
        return builder.toString();
    }

    private boolean aiEnabled() {
        return aiProperties.isEnabled() && StringUtils.hasText(aiProperties.getApiKey());
    }

    private String normalizeChatCompletionsUrl() {
        String baseUrl = aiProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> extractKeywords(String question) {
        String normalized = question
                .replaceAll("[什么怎么为什么如何哪里哪个是否能不能吗呢啊的了和以及、，。！？?;；:：]", " ")
                .trim();
        List<String> keywords = Stream.of(normalized.split("\\s+"))
                .filter(StringUtils::hasText)
                .filter(keyword -> keyword.length() >= 2)
                .limit(5)
                .toList();
        return keywords.isEmpty() ? List.of(question) : keywords;
    }
}
