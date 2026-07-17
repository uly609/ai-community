package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import com.ai.aicommunity.entity.MqOutboxMessage;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.entity.TrainingCampOrder;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.TrainingCampMapper;
import com.ai.aicommunity.mapper.TrainingCampOrderMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingCampServiceTest {

    @Mock
    private TrainingCampMapper trainingCampMapper;
    @Mock
    private TrainingCampOrderMapper orderMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MqOutboxService mqOutboxService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TrainingCampRedisCircuitBreaker redisCircuitBreaker;

    private TrainingCampService trainingCampService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TrainingCamp.class);
        trainingCampService = new TrainingCampService(
                trainingCampMapper,
                orderMapper,
                redisTemplate,
                mqOutboxService,
                objectMapper,
                redisCircuitBreaker
        );
    }

    @Test
    void duplicateOrderMessageDoesNotCreateAnotherOrder() {
        TrainingCampOrder existingOrder = new TrainingCampOrder();
        existingOrder.setStatus(0);
        when(orderMapper.selectOne(any())).thenReturn(existingOrder);

        trainingCampService.createOrderFromMessage(new TrainingCampOrderMessage(1L, 2L));

        verify(trainingCampMapper, never()).update(any(), any());
        verify(orderMapper, never()).insert(any(TrainingCampOrder.class));
        verify(mqOutboxService, never()).savePendingDelay(any(), any(), anyInt());
    }

    @Test
    void canceledOrderCannotBePaid() {
        TrainingCampOrder order = new TrainingCampOrder();
        order.setId(1L);
        order.setUserId(2L);
        order.setStatus(2);
        when(orderMapper.selectById(1L)).thenReturn(order);

        UserHolder.saveUserId(2L);
        try {
            assertThrows(BusinessException.class, () -> trainingCampService.pay(1L));
        } finally {
            UserHolder.remove();
        }

        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void openRedisCircuitRejectsEnrollmentBeforeAccessingDependencies() {
        when(redisCircuitBreaker.isOpen()).thenReturn(true);
        UserHolder.saveUserId(2L);
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> trainingCampService.enroll(1L, "qualification-token"));
            org.junit.jupiter.api.Assertions.assertEquals("报名服务暂不可用，请稍后重试", exception.getMessage());
        } finally {
            UserHolder.remove();
        }

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
        verify(trainingCampMapper, never()).selectById(anyLong());
    }

    @Test
    void missingQualificationTokenIsRejectedWhenCampRequiresQualification() {
        TrainingCamp camp = activeCamp();
        camp.setQualificationRequired(true);
        when(trainingCampMapper.selectById(1L)).thenReturn(camp);

        UserHolder.saveUserId(2L);
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> trainingCampService.enroll(1L, null));
            org.junit.jupiter.api.Assertions.assertEquals("请先申请报名资格", exception.getMessage());
        } finally {
            UserHolder.remove();
        }

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
        verify(trainingCampMapper).selectById(1L);
    }

    @Test
    void missingQualificationTokenIsRejectedDuringStartBurstWindow() {
        TrainingCamp camp = activeCamp();
        camp.setQualificationRequired(false);
        when(trainingCampMapper.selectById(1L)).thenReturn(camp);

        UserHolder.saveUserId(2L);
        try {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> trainingCampService.enroll(1L, null));
            org.junit.jupiter.api.Assertions.assertEquals("请先申请报名资格", exception.getMessage());
        } finally {
            UserHolder.remove();
        }

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
        verify(trainingCampMapper).selectById(1L);
    }

    @Test
    void dbStockShortageSchedulesRedisRollbackThroughOutbox() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(trainingCampMapper.update(any(), any())).thenReturn(0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        MqOutboxMessage outboxMessage = new MqOutboxMessage();
        when(mqOutboxService.savePending(eq(RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC), any()))
                .thenReturn(outboxMessage);

        trainingCampService.createOrderFromMessage(new TrainingCampOrderMessage(1L, 2L));

        verify(mqOutboxService).savePending(
                eq(RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC), any());
        verify(mqOutboxService).sendNow(eq(outboxMessage), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
        verify(orderMapper, never()).insert(any(TrainingCampOrder.class));
    }

    private TrainingCamp activeCamp() {
        TrainingCamp camp = new TrainingCamp();
        camp.setId(1L);
        camp.setStock(10);
        camp.setStartTime(LocalDateTime.now().minusMinutes(1));
        camp.setEndTime(LocalDateTime.now().plusMinutes(10));
        camp.setQualificationRequired(false);
        return camp;
    }
}
