package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.ArticleAiSummaryMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMQConfig.ARTICLE_AI_SUMMARY_TOPIC,
        consumerGroup = RocketMQConfig.ARTICLE_AI_SUMMARY_CONSUMER_GROUP,
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 5
)
public class ArticleAiSummaryConsumer implements RocketMQListener<ArticleAiSummaryMessage> {

    private final ArticleAiSummaryService articleAiSummaryService;

    public ArticleAiSummaryConsumer(ArticleAiSummaryService articleAiSummaryService) {
        this.articleAiSummaryService = articleAiSummaryService;
    }

    @Override
    public void onMessage(ArticleAiSummaryMessage message) {
        articleAiSummaryService.generateAndSave(message);
    }
}
