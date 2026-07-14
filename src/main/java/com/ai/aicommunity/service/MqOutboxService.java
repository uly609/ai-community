package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import com.ai.aicommunity.dto.TrainingCampRedisRollbackMessage;
import com.ai.aicommunity.dto.CommunityNotificationMessage;
import com.ai.aicommunity.dto.ArticleAiSummaryMessage;
import com.ai.aicommunity.entity.MqOutboxMessage;
import com.ai.aicommunity.mapper.MqOutboxMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MqOutboxService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SENT = 1;
    private static final int STATUS_FAILED = 2;
    private static final int MAX_RETRY_COUNT = 5;

    private final MqOutboxMessageMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public MqOutboxService(MqOutboxMessageMapper outboxMapper,
                           RocketMQTemplate rocketMQTemplate,
                           ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveAndSend(String topic, Object payload, String payloadJson) {
        MqOutboxMessage message = savePending(topic, payloadJson);
        sendNow(message, payload);
    }

    /**
     * Persists the message first, then lets the RocketMQ client wait for the broker
     * acknowledgement off the HTTP request thread. The scheduled retry remains the
     * durable fallback when this first delivery does not succeed.
     */
    public void saveAndSendAsync(String topic, Object payload, String payloadJson) {
        MqOutboxMessage message = savePending(topic, payloadJson);
        try {
            rocketMQTemplate.asyncSend(topic, payload, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    markSent(message.getId());
                }

                @Override
                public void onException(Throwable throwable) {
                    markFailed(message, throwable);
                }
            });
        } catch (Exception e) {
            markFailed(message, e);
        }
    }

    public MqOutboxMessage savePending(String topic, String payloadJson) {
        LocalDateTime now = LocalDateTime.now();
        MqOutboxMessage message = new MqOutboxMessage();
        message.setTopic(topic);
        message.setPayload(payloadJson);
        message.setStatus(STATUS_PENDING);
        message.setRetryCount(0);
        message.setNextRetryTime(now);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        outboxMapper.insert(message);
        return message;
    }

    public void sendNow(MqOutboxMessage message, Object payload) {
        sendMessage(message, payload);
    }

    @Scheduled(fixedDelay = 5000)
    public void retryPendingMessages() {
        List<MqOutboxMessage> messages = outboxMapper.selectList(
                new LambdaQueryWrapper<MqOutboxMessage>()
                        .in(MqOutboxMessage::getStatus, STATUS_PENDING, STATUS_FAILED)
                        .le(MqOutboxMessage::getNextRetryTime, LocalDateTime.now())
                        .lt(MqOutboxMessage::getRetryCount, MAX_RETRY_COUNT)
                        .orderByAsc(MqOutboxMessage::getCreateTime)
                        .last("LIMIT 20")
        );

        for (MqOutboxMessage message : messages) {
            try {
                sendMessage(message, parsePayload(message));
            } catch (Exception e) {
                markFailed(message, e);
            }
        }
    }

    private Object parsePayload(MqOutboxMessage message) {
        try {
            if (RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), TrainingCampOrderMessage.class);
            }
            if (RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), TrainingCampRedisRollbackMessage.class);
            }
            if (RocketMQConfig.COMMUNITY_NOTIFICATION_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), CommunityNotificationMessage.class);
            }
            if (RocketMQConfig.ARTICLE_AI_SUMMARY_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), ArticleAiSummaryMessage.class);
            }
            return message.getPayload();
        } catch (Exception e) {
            throw new IllegalStateException("解析Outbox消息失败", e);
        }
    }

    private void sendMessage(MqOutboxMessage message, Object payload) {
        try {
            rocketMQTemplate.syncSend(message.getTopic(), payload);
            markSent(message.getId());
        } catch (Exception e) {
            markFailed(message, e);
        }
    }

    private void markSent(Long id) {
        outboxMapper.update(
                null,
                new LambdaUpdateWrapper<MqOutboxMessage>()
                        .eq(MqOutboxMessage::getId, id)
                        .ne(MqOutboxMessage::getStatus, STATUS_SENT)
                        .set(MqOutboxMessage::getStatus, STATUS_SENT)
                        .set(MqOutboxMessage::getUpdateTime, LocalDateTime.now())
        );
    }

    private void markFailed(MqOutboxMessage message, Throwable e) {
        int retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(Math.min(60, 5L * (retryCount + 1)));
        outboxMapper.update(
                null,
                new LambdaUpdateWrapper<MqOutboxMessage>()
                        .eq(MqOutboxMessage::getId, message.getId())
                        .ne(MqOutboxMessage::getStatus, STATUS_SENT)
                        .set(MqOutboxMessage::getStatus, STATUS_FAILED)
                        .set(MqOutboxMessage::getRetryCount, retryCount + 1)
                        .set(MqOutboxMessage::getNextRetryTime, nextRetryTime)
                        .set(MqOutboxMessage::getErrorMessage, truncate(e.getMessage()))
                        .set(MqOutboxMessage::getUpdateTime, LocalDateTime.now())
        );
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
