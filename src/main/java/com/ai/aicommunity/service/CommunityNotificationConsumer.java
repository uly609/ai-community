package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.CommunityNotificationMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mq.community.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMQConfig.COMMUNITY_NOTIFICATION_TOPIC,
        consumerGroup = RocketMQConfig.COMMUNITY_NOTIFICATION_CONSUMER_GROUP,
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 5
)
public class CommunityNotificationConsumer implements RocketMQListener<CommunityNotificationMessage> {

    private final NotificationService notificationService;

    public CommunityNotificationConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onMessage(CommunityNotificationMessage message) {
        notificationService.createFromMessage(message);
    }
}
