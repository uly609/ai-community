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

    // 保存 Outbox 后立刻同步发 MQ，适合不在乎请求线程多等一下的场景。
    public void saveAndSend(String topic, Object payload, String payloadJson) {
        MqOutboxMessage message = savePending(topic, payloadJson);
        sendNow(message, payload);
    }

    // 保存 Outbox 后异步发 MQ：接口不用等 Broker 确认，失败再靠定时任务补发。
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

    // 只保存一条待发送消息，不马上发。事务里常用这个，等提交后再发。
    public MqOutboxMessage savePending(String topic, String payloadJson) {
        return savePending(topic, payloadJson, null);
    }

    // 保存一条 RocketMQ 延迟消息。delaySeconds 交给 Broker 控制到点投递。
    public MqOutboxMessage savePendingDelay(String topic, String payloadJson, int delaySeconds) {
        return savePending(topic, payloadJson, delaySeconds);
    }

    private MqOutboxMessage savePending(String topic, String payloadJson, Integer delaySeconds) {
        LocalDateTime now = LocalDateTime.now();
        MqOutboxMessage message = new MqOutboxMessage();
        message.setTopic(topic);
        message.setPayload(payloadJson);
        message.setStatus(STATUS_PENDING);
        message.setRetryCount(0);
        message.setDelaySeconds(delaySeconds);
        message.setNextRetryTime(now);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        // 先把“我要发的消息”落库，后面 MQ 发送失败才有东西可以补偿重试。
        outboxMapper.insert(message);
        return message;
    }

    // 立即发送一条已经保存过的 Outbox 消息。
    public void sendNow(MqOutboxMessage message, Object payload) {
        sendMessage(message, payload);
    }

    @Scheduled(fixedDelay = 5000)
    // 定时补发 Pending/Failed 消息，避免 MQ 临时失败导致消息永远丢在表里。
    public void retryPendingMessages() {
        // 扫描还没发送成功的 Outbox 消息，RocketMQ 临时失败时靠这里补发。
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

    // 根据 topic 把 JSON payload 还原成对应消息对象。
    private Object parsePayload(MqOutboxMessage message) {
        try {
            if (RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), TrainingCampOrderMessage.class);
            }
            if (RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC.equals(message.getTopic())) {
                return objectMapper.readValue(message.getPayload(), TrainingCampRedisRollbackMessage.class);
            }
            if (RocketMQConfig.TRAINING_CAMP_ORDER_TIMEOUT_TOPIC.equals(message.getTopic())) {
                return Long.valueOf(message.getPayload());
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

    // 真正发 RocketMQ，成功标 SENT，失败标 FAILED 并安排下次重试。
    private void sendMessage(MqOutboxMessage message, Object payload) {
        try {
            if (message.getDelaySeconds() != null && message.getDelaySeconds() > 0) {
                rocketMQTemplate.syncSendDelayTimeSeconds(message.getTopic(), payload, message.getDelaySeconds());
            } else {
                rocketMQTemplate.syncSend(message.getTopic(), payload);
            }
            markSent(message.getId());
        } catch (Exception e) {
            markFailed(message, e);
        }
    }

    // 标记消息已经发成功。
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

    // 标记消息发送失败，并设置下一次重试时间。
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

    // 错误信息太长会塞不进数据库，这里截断一下。
    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
