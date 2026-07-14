package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mq.training-camp.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMQConfig.TRAINING_CAMP_ORDER_TIMEOUT_TOPIC,
        consumerGroup = RocketMQConfig.TRAINING_CAMP_ORDER_TIMEOUT_CONSUMER_GROUP,
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 5
)
public class TrainingCampOrderTimeoutConsumer implements RocketMQListener<Long> {

    private final TrainingCampService trainingCampService;

    public TrainingCampOrderTimeoutConsumer(TrainingCampService trainingCampService) {
        this.trainingCampService = trainingCampService;
    }

    @Override
    public void onMessage(Long orderId) {
        trainingCampService.cancelExpiredOrder(orderId);
    }
}
