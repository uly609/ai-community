package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampRedisRollbackMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mq.training-camp.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC,
        consumerGroup = RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_CONSUMER_GROUP,
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 5
)
public class TrainingCampRedisRollbackConsumer implements RocketMQListener<TrainingCampRedisRollbackMessage> {

    private final TrainingCampService trainingCampService;

    public TrainingCampRedisRollbackConsumer(TrainingCampService trainingCampService) {
        this.trainingCampService = trainingCampService;
    }

    @Override
    public void onMessage(TrainingCampRedisRollbackMessage message) {
        trainingCampService.rollbackRedisQualification(message);
    }
}
