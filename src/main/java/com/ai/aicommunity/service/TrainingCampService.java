package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampDTO;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import com.ai.aicommunity.dto.TrainingCampRedisRollbackMessage;
import com.ai.aicommunity.entity.MqOutboxMessage;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.entity.TrainingCampOrder;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.TrainingCampMapper;
import com.ai.aicommunity.mapper.TrainingCampOrderMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class TrainingCampService {

    private static final String CAMP_STOCK_KEY = "training:camp:stock:";
    private static final String CAMP_USERS_KEY = "training:camp:users:";
    private static final String CAMP_RATE_LIMIT_KEY = "training:camp:rate:";
    private static final Integer ORDER_STATUS_PENDING = 0;
    private static final Integer ORDER_STATUS_PAID = 1;
    private static final Integer ORDER_STATUS_CANCELED = 2;
    private static final int PAY_TIMEOUT_MINUTES = 15;
    private static final long PAY_TIMEOUT_SECONDS = PAY_TIMEOUT_MINUTES * 60L;
    private static final int ENROLL_RATE_LIMIT_SECONDS = 10;
    private static final int ENROLL_RATE_LIMIT_COUNT = 5;
    private static final RedisScript<Long> SECKILL_SCRIPT = RedisScript.of(
            "local stock = tonumber(redis.call('get', KEYS[1]) or '-1') " +
            "if stock < 0 then return 3 end " +
                    "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end " +
                    "if stock <= 0 then return 1 end " +
                    "redis.call('decr', KEYS[1]) " +
                    "redis.call('sadd', KEYS[2], ARGV[1]) " +
                    "return 0",
            Long.class
    );
    private static final RedisScript<Long> ROLLBACK_REDIS_QUALIFICATION_SCRIPT = RedisScript.of(
            "local removed = redis.call('srem', KEYS[2], ARGV[1]) " +
                    "if removed == 1 then return redis.call('incr', KEYS[1]) end " +
                    "return 0",
            Long.class
    );
    private static final RedisScript<Long> ENROLL_RATE_LIMIT_SCRIPT = RedisScript.of(
            "local count = redis.call('incr', KEYS[1]) " +
                    "if count == 1 then redis.call('expire', KEYS[1], ARGV[1]) end " +
                    "if count > tonumber(ARGV[2]) then return 0 end " +
                    "return 1",
            Long.class
    );

    private final TrainingCampMapper trainingCampMapper;
    private final TrainingCampOrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final MqOutboxService mqOutboxService;
    private final ObjectMapper objectMapper;
    private final TrainingCampRedisCircuitBreaker redisCircuitBreaker;

    public TrainingCampService(TrainingCampMapper trainingCampMapper,
                               TrainingCampOrderMapper orderMapper,
                               StringRedisTemplate redisTemplate,
                               RocketMQTemplate rocketMQTemplate,
                               MqOutboxService mqOutboxService,
                               ObjectMapper objectMapper,
                               TrainingCampRedisCircuitBreaker redisCircuitBreaker) {
        this.trainingCampMapper = trainingCampMapper;
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.mqOutboxService = mqOutboxService;
        this.objectMapper = objectMapper;
        this.redisCircuitBreaker = redisCircuitBreaker;
    }

    public Long create(TrainingCampDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }

        TrainingCamp camp = new TrainingCamp();
        camp.setTitle(dto.getTitle());
        camp.setDescription(dto.getDescription());
        camp.setStock(dto.getStock());
        camp.setStartTime(dto.getStartTime());
        camp.setEndTime(dto.getEndTime());
        camp.setCreateTime(LocalDateTime.now());
        camp.setUpdateTime(LocalDateTime.now());
        trainingCampMapper.insert(camp);

        resetStockCache(camp);
        return camp.getId();
    }

    public Page<TrainingCamp> page(Integer current, Integer size) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        return trainingCampMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<TrainingCamp>().orderByDesc(TrainingCamp::getCreateTime)
        );
    }

    public void preload(Long campId) {
        TrainingCamp camp = getCamp(campId);
        resetStockCache(camp);
    }

    public void enroll(Long campId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        if (redisCircuitBreaker.isOpen()) {
            throw new BusinessException("报名服务暂不可用，请稍后重试");
        }

        TrainingCamp camp = getCamp(campId);
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(camp.getStartTime())) {
            throw new BusinessException("报名尚未开始");
        }
        if (now.isAfter(camp.getEndTime())) {
            throw new BusinessException("报名已结束");
        }

        Long result;
        try {
            checkEnrollRateLimit(campId, userId);
            ensureStockPreloaded(camp);
            result = redisTemplate.execute(
                    SECKILL_SCRIPT,
                    Arrays.asList(buildStockKey(campId), buildUsersKey(campId)),
                    String.valueOf(userId)
            );
            redisCircuitBreaker.recordSuccess();
        } catch (DataAccessException e) {
            redisCircuitBreaker.recordFailure();
            throw new BusinessException("报名服务暂不可用，请稍后重试");
        }

        if (result == null || result == 3) {
            throw new BusinessException("报名名额未初始化");
        }
        if (result == 1) {
            throw new BusinessException("名额已抢完");
        }
        if (result == 2) {
            throw new BusinessException("不能重复报名");
        }

        TrainingCampOrderMessage message = new TrainingCampOrderMessage(campId, userId);
        try {
            mqOutboxService.saveAndSend(
                    RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC,
                    message,
                    objectMapper.writeValueAsString(message)
            );
        } catch (Exception e) {
            rollbackRedisQualification(campId, userId);
            throw new BusinessException("报名请求繁忙，请稍后重试");
        }
    }

    public Page<TrainingCampOrder> myOrders(Integer current, Integer size) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }

        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        return orderMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<TrainingCampOrder>()
                        .eq(TrainingCampOrder::getUserId, userId)
                        .orderByDesc(TrainingCampOrder::getCreateTime)
        );
    }

    @Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
    public void pay(Long orderId) {
        TrainingCampOrder order = getOwnedOrder(orderId);
        if (ORDER_STATUS_PAID.equals(order.getStatus())) {
            return;
        }
        if (ORDER_STATUS_CANCELED.equals(order.getStatus())) {
            throw new BusinessException("订单已取消");
        }
        if (order.getPayExpireTime() != null && LocalDateTime.now().isAfter(order.getPayExpireTime())) {
            cancelPendingOrder(order);
            throw new BusinessException("订单已超时，请重新报名");
        }

        int updated = orderMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCampOrder>()
                        .eq(TrainingCampOrder::getId, orderId)
                        .eq(TrainingCampOrder::getStatus, ORDER_STATUS_PENDING)
                        .set(TrainingCampOrder::getStatus, ORDER_STATUS_PAID)
                        .set(TrainingCampOrder::getUpdateTime, LocalDateTime.now())
        );
        if (updated == 0) {
            throw new BusinessException("订单状态已变化，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId) {
        TrainingCampOrder order = getOwnedOrder(orderId);
        if (ORDER_STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException("已支付订单不能取消");
        }
        if (ORDER_STATUS_CANCELED.equals(order.getStatus())) {
            return;
        }
        cancelPendingOrder(order);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createOrderFromMessage(TrainingCampOrderMessage message) {
        TrainingCampOrder existingOrder = orderMapper.selectOne(
                new LambdaQueryWrapper<TrainingCampOrder>()
                        .eq(TrainingCampOrder::getCampId, message.getCampId())
                        .eq(TrainingCampOrder::getUserId, message.getUserId())
        );
        if (existingOrder != null && !ORDER_STATUS_CANCELED.equals(existingOrder.getStatus())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime payExpireTime = now.plusMinutes(PAY_TIMEOUT_MINUTES);
        if (!reserveDbStock(message.getCampId())) {
            scheduleRedisQualificationRollback(message.getCampId(), message.getUserId());
            return;
        }

        Long orderId;
        if (existingOrder != null) {
            existingOrder.setStatus(ORDER_STATUS_PENDING);
            existingOrder.setPayExpireTime(payExpireTime);
            existingOrder.setUpdateTime(now);
            orderMapper.updateById(existingOrder);
            orderId = existingOrder.getId();
        } else {
            TrainingCampOrder order = new TrainingCampOrder();
            order.setCampId(message.getCampId());
            order.setUserId(message.getUserId());
            order.setStatus(ORDER_STATUS_PENDING);
            order.setPayExpireTime(payExpireTime);
            order.setCreateTime(now);
            order.setUpdateTime(now);
            orderMapper.insert(order);
            orderId = order.getId();
        }

        sendOrderTimeoutMessage(orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelExpiredOrder(Long orderId) {
        TrainingCampOrder order = orderMapper.selectById(orderId);
        if (order == null || !ORDER_STATUS_PENDING.equals(order.getStatus())) {
            return;
        }
        if (order.getPayExpireTime() != null && LocalDateTime.now().isBefore(order.getPayExpireTime())) {
            return;
        }
        cancelPendingOrder(order);
    }

    private TrainingCamp getCamp(Long campId) {
        TrainingCamp camp = trainingCampMapper.selectById(campId);
        if (camp == null) {
            throw new BusinessException("训练营不存在");
        }
        return camp;
    }

    private TrainingCampOrder getOwnedOrder(Long orderId) {
        TrainingCampOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        Long userId = UserHolder.getUserId();
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException("只能操作自己的订单");
        }
        return order;
    }

    private void resetStockCache(TrainingCamp camp) {
        cacheStock(camp);
        redisTemplate.delete(buildUsersKey(camp.getId()));
    }

    private void cacheStock(TrainingCamp camp) {
        Duration ttl = Duration.between(LocalDateTime.now(), camp.getEndTime().plusMinutes(10));
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(10);
        }
        redisTemplate.opsForValue().set(buildStockKey(camp.getId()), String.valueOf(camp.getStock()), ttl);
    }

    private void ensureStockPreloaded(TrainingCamp camp) {
        String stockKey = buildStockKey(camp.getId());
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
            cacheStock(camp);
        }
    }

    private boolean reserveDbStock(Long campId) {
        int updated = trainingCampMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCamp>()
                        .eq(TrainingCamp::getId, campId)
                        .gt(TrainingCamp::getStock, 0)
                        .setSql("stock = stock - 1")
                        .set(TrainingCamp::getUpdateTime, LocalDateTime.now())
        );
        return updated > 0;
    }

    private void cancelPendingOrder(TrainingCampOrder order) {
        int updated = orderMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCampOrder>()
                        .eq(TrainingCampOrder::getId, order.getId())
                        .eq(TrainingCampOrder::getStatus, ORDER_STATUS_PENDING)
                        .set(TrainingCampOrder::getStatus, ORDER_STATUS_CANCELED)
                        .set(TrainingCampOrder::getUpdateTime, LocalDateTime.now())
        );
        if (updated == 0) {
            return;
        }
        restoreDbStock(order.getCampId());
        scheduleRedisQualificationRollback(order.getCampId(), order.getUserId());
    }

    private void scheduleRedisQualificationRollback(Long campId, Long userId) {
        TrainingCampRedisRollbackMessage message = new TrainingCampRedisRollbackMessage(
                campId, userId);
        MqOutboxMessage outboxMessage;
        try {
            outboxMessage = mqOutboxService.savePending(
                    RocketMQConfig.TRAINING_CAMP_REDIS_ROLLBACK_TOPIC,
                    objectMapper.writeValueAsString(message)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Redis库存回补消息保存失败", e);
        }
        runAfterTransactionCommit(() -> mqOutboxService.sendNow(outboxMessage, message));
    }

    private void restoreDbStock(Long campId) {
        int updated = trainingCampMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCamp>()
                        .eq(TrainingCamp::getId, campId)
                        .setSql("stock = stock + 1")
                        .set(TrainingCamp::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new IllegalStateException("训练营库存回补失败");
        }
    }

    public void rollbackRedisQualification(TrainingCampRedisRollbackMessage message) {
        rollbackRedisQualification(message.getCampId(), message.getUserId());
    }

    private void rollbackRedisQualification(Long campId, Long userId) {
        redisTemplate.execute(
                ROLLBACK_REDIS_QUALIFICATION_SCRIPT,
                Arrays.asList(buildStockKey(campId), buildUsersKey(campId)),
                String.valueOf(userId)
        );
    }

    private void runAfterTransactionCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void sendOrderTimeoutMessage(Long orderId) {
        rocketMQTemplate.syncSendDelayTimeSeconds(
                RocketMQConfig.TRAINING_CAMP_ORDER_TIMEOUT_TOPIC,
                orderId,
                PAY_TIMEOUT_SECONDS
        );
    }

    private String buildStockKey(Long campId) {
        return CAMP_STOCK_KEY + campId;
    }

    private String buildUsersKey(Long campId) {
        return CAMP_USERS_KEY + campId;
    }

    private void checkEnrollRateLimit(Long campId, Long userId) {
        Long allowed = redisTemplate.execute(
                ENROLL_RATE_LIMIT_SCRIPT,
                java.util.Collections.singletonList(CAMP_RATE_LIMIT_KEY + campId + ":" + userId),
                String.valueOf(ENROLL_RATE_LIMIT_SECONDS),
                String.valueOf(ENROLL_RATE_LIMIT_COUNT)
        );
        if (!Long.valueOf(1L).equals(allowed)) {
            throw new BusinessException("操作过于频繁，请稍后重试");
        }
    }
}
