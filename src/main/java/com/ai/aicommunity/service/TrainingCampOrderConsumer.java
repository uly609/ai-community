package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mq.training-camp.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC,
        consumerGroup = RocketMQConfig.TRAINING_CAMP_ORDER_CONSUMER_GROUP
)
public class TrainingCampOrderConsumer implements RocketMQListener<TrainingCampOrderMessage> {

    private final TrainingCampService trainingCampService;

    public TrainingCampOrderConsumer(TrainingCampService trainingCampService) {
        this.trainingCampService = trainingCampService;
    }

    @Override
    public void onMessage(TrainingCampOrderMessage message) {
        trainingCampService.createOrderFromMessage(message);
    }
}
