package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampDTO;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.entity.TrainingCampOrder;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.TrainingCampMapper;
import com.ai.aicommunity.mapper.TrainingCampOrderMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class TrainingCampService {

    private static final String CAMP_STOCK_KEY = "training:camp:stock:";
    private static final String CAMP_USERS_KEY = "training:camp:users:";
    private static final Integer ORDER_STATUS_PENDING = 0;
    private static final Integer ORDER_STATUS_PAID = 1;
    private static final Integer ORDER_STATUS_CANCELED = 2;
    private static final int PAY_TIMEOUT_MINUTES = 15;
    private static final long PAY_TIMEOUT_SECONDS = PAY_TIMEOUT_MINUTES * 60L;
    private static final RedisScript<Long> SECKILL_SCRIPT = RedisScript.of(
            "local stock = tonumber(redis.call('get', KEYS[1]) or '-1') " +
                    "if stock < 0 then return 3 end " +
                    "if stock <= 0 then return 1 end " +
                    "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end " +
                    "redis.call('decr', KEYS[1]) " +
                    "redis.call('sadd', KEYS[2], ARGV[1]) " +
                    "return 0",
            Long.class
    );

    private final TrainingCampMapper trainingCampMapper;
    private final TrainingCampOrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    public TrainingCampService(TrainingCampMapper trainingCampMapper,
                               TrainingCampOrderMapper orderMapper,
                               StringRedisTemplate redisTemplate,
                               RocketMQTemplate rocketMQTemplate) {
        this.trainingCampMapper = trainingCampMapper;
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
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

        preloadStock(camp);
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
        preloadStock(camp);
    }

    public void enroll(Long campId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }

        TrainingCamp camp = getCamp(campId);
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(camp.getStartTime())) {
            throw new BusinessException("报名尚未开始");
        }
        if (now.isAfter(camp.getEndTime())) {
            throw new BusinessException("报名已结束");
        }

        ensureStockPreloaded(camp);
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(buildStockKey(campId), buildUsersKey(campId)),
                String.valueOf(userId)
        );

        if (result == null || result == 3) {
            throw new BusinessException("报名名额未初始化");
        }
        if (result == 1) {
            throw new BusinessException("名额已抢完");
        }
        if (result == 2) {
            throw new BusinessException("不能重复报名");
        }

        try {
            rocketMQTemplate.syncSend(
                    RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC,
                    new TrainingCampOrderMessage(campId, userId)
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
            rollbackRedisQualification(message.getCampId(), message.getUserId());
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

    private void preloadStock(TrainingCamp camp) {
        Duration ttl = Duration.between(LocalDateTime.now(), camp.getEndTime().plusMinutes(10));
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(10);
        }
        redisTemplate.opsForValue().set(buildStockKey(camp.getId()), String.valueOf(camp.getStock()), ttl);
        redisTemplate.delete(buildUsersKey(camp.getId()));
    }

    private void ensureStockPreloaded(TrainingCamp camp) {
        String stockKey = buildStockKey(camp.getId());
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
            preloadStock(camp);
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
        restoreStock(order.getCampId(), order.getUserId());
    }

    private void restoreStock(Long campId, Long userId) {
        trainingCampMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCamp>()
                        .eq(TrainingCamp::getId, campId)
                        .setSql("stock = stock + 1")
                        .set(TrainingCamp::getUpdateTime, LocalDateTime.now())
        );
        rollbackRedisQualification(campId, userId);
    }

    private void rollbackRedisQualification(Long campId, Long userId) {
        redisTemplate.opsForValue().increment(buildStockKey(campId));
        redisTemplate.opsForSet().remove(buildUsersKey(campId), String.valueOf(userId));
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
}
