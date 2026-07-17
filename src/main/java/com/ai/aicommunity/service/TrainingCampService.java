package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.TrainingCampDTO;
import com.ai.aicommunity.dto.TrainingCampOrderMessage;
import com.ai.aicommunity.dto.TrainingCampQualificationResponse;
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
import java.util.UUID;

@Service
public class TrainingCampService {

    private static final String CAMP_STOCK_KEY = "training:camp:stock:";
    private static final String CAMP_USERS_KEY = "training:camp:users:";
    private static final String CAMP_RATE_LIMIT_KEY = "training:camp:rate:";
    private static final String CAMP_QUALIFICATION_KEY = "training:camp:qualification:";
    private static final Integer ORDER_STATUS_PENDING = 0;
    private static final Integer ORDER_STATUS_PAID = 1;
    private static final Integer ORDER_STATUS_CANCELED = 2;
    private static final int PAY_TIMEOUT_MINUTES = 15;
    private static final long PAY_TIMEOUT_SECONDS = PAY_TIMEOUT_MINUTES * 60L;
    private static final int ENROLL_RATE_LIMIT_SECONDS = 10;
    private static final int ENROLL_RATE_LIMIT_COUNT = 5;
    private static final int QUALIFICATION_TOKEN_SECONDS = 60;
    private static final Duration QUALIFICATION_FORCE_BEFORE_START = Duration.ofMinutes(5);
    private static final Duration QUALIFICATION_FORCE_AFTER_START = Duration.ofMinutes(30);
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
    private static final RedisScript<Long> SECKILL_WITH_QUALIFICATION_SCRIPT = RedisScript.of(
            "local owner = redis.call('get', KEYS[3]) " +
                    "if not owner or owner ~= ARGV[1] then return 4 end " +
                    "local stock = tonumber(redis.call('get', KEYS[1]) or '-1') " +
                    "if stock < 0 then return 3 end " +
                    "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end " +
                    "if stock <= 0 then return 1 end " +
                    "redis.call('del', KEYS[3]) " +
                    "redis.call('decr', KEYS[1]) " +
                    "redis.call('sadd', KEYS[2], ARGV[1]) " +
                    "return 0",
            Long.class
    );
    private static final RedisScript<Long> APPLY_QUALIFICATION_SCRIPT = RedisScript.of(
            "local stock = tonumber(redis.call('get', KEYS[1]) or '-1') " +
                    "if stock < 0 then return 3 end " +
                    "if stock <= 0 then return 1 end " +
                    "redis.call('set', KEYS[2], ARGV[1], 'EX', ARGV[2]) " +
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
    private final MqOutboxService mqOutboxService;
    private final ObjectMapper objectMapper;
    private final TrainingCampRedisCircuitBreaker redisCircuitBreaker;

    public TrainingCampService(TrainingCampMapper trainingCampMapper,
                               TrainingCampOrderMapper orderMapper,
                               StringRedisTemplate redisTemplate,
                               MqOutboxService mqOutboxService,
                               ObjectMapper objectMapper,
                               TrainingCampRedisCircuitBreaker redisCircuitBreaker) {
        this.trainingCampMapper = trainingCampMapper;
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
        this.mqOutboxService = mqOutboxService;
        this.objectMapper = objectMapper;
        this.redisCircuitBreaker = redisCircuitBreaker;
    }

    // 创建训练营：写 MySQL，并把库存预热到 Redis。
    public Long create(TrainingCampDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }

        TrainingCamp camp = new TrainingCamp();
        camp.setTitle(dto.getTitle());
        camp.setDescription(dto.getDescription());
        camp.setStock(dto.getStock());
        camp.setQualificationRequired(Boolean.TRUE.equals(dto.getQualificationRequired()));
        camp.setStartTime(dto.getStartTime());
        camp.setEndTime(dto.getEndTime());
        camp.setCreateTime(LocalDateTime.now());
        camp.setUpdateTime(LocalDateTime.now());
        trainingCampMapper.insert(camp);

        resetStockCache(camp);
        return camp.getId();
    }

    // 分页查询训练营列表。
    public Page<TrainingCamp> page(Integer current, Integer size) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        return trainingCampMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<TrainingCamp>().orderByDesc(TrainingCamp::getCreateTime)
        );
    }

    // 手动预热某个训练营库存，压测或活动开始前可以先调这个。
    public void preload(Long campId) {
        TrainingCamp camp = getCamp(campId);
        resetStockCache(camp);
    }

    // 申请报名资格 token：这是报名门票，不是登录 JWT，默认 60 秒有效。
    public TrainingCampQualificationResponse applyQualification(Long campId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        if (redisCircuitBreaker.isOpen()) {
            throw new BusinessException("报名服务暂不可用，请稍后重试");
        }

        TrainingCamp camp = getCamp(campId);
        validateEnrollmentWindow(camp);

        // 这个不是登录 JWT，是这一次报名用的短期资格票，60 秒后自动失效。
        String qualificationToken = UUID.randomUUID().toString();
        Long result;
        try {
            checkEnrollRateLimit(campId, userId);
            ensureStockPreloaded(camp);
            result = redisTemplate.execute(
                    APPLY_QUALIFICATION_SCRIPT,
                    Arrays.asList(buildStockKey(campId), buildQualificationKey(campId, qualificationToken)),
                    String.valueOf(userId), String.valueOf(QUALIFICATION_TOKEN_SECONDS)
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

        return new TrainingCampQualificationResponse(qualificationToken, QUALIFICATION_TOKEN_SECONDS);
    }

    // 报名入口：Redis Lua 先抢资格，再通过 Outbox + RocketMQ 异步创建 MySQL 订单。
    public void enroll(Long campId, String qualificationToken) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        if (redisCircuitBreaker.isOpen()) {
            throw new BusinessException("报名服务暂不可用，请稍后重试");
        }

        TrainingCamp camp = getCamp(campId);
        validateEnrollmentWindow(camp);
        // 字段强制或开抢时间窗口内，都会要求先拿 qualificationToken。
        boolean qualificationRequired = isQualificationRequired(camp);
        if (qualificationRequired && (qualificationToken == null || qualificationToken.isBlank())) {
            throw new BusinessException("请先申请报名资格");
        }

        Long result;
        try {
            checkEnrollRateLimit(campId, userId);
            ensureStockPreloaded(camp);
            if (qualificationRequired) {
                // 高峰链路：先验资格 token，再验库存和是否报名过，最后预扣 Redis 库存。
                result = redisTemplate.execute(
                        SECKILL_WITH_QUALIFICATION_SCRIPT,
                        Arrays.asList(
                                buildStockKey(campId),
                                buildUsersKey(campId),
                                buildQualificationKey(campId, qualificationToken)
                        ),
                        String.valueOf(userId)
                );
            } else {
                // 普通链路：少一次资格 token 校验，但库存预扣和一人一单还是用 Lua 原子保证。
                result = redisTemplate.execute(
                        SECKILL_SCRIPT,
                        Arrays.asList(buildStockKey(campId), buildUsersKey(campId)),
                        String.valueOf(userId)
                );
            }
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
        if (result == 4) {
            throw new BusinessException("报名资格无效或已过期");
        }

        TrainingCampOrderMessage message = new TrainingCampOrderMessage(campId, userId);
        try {
            // 先落 Outbox，再异步发 MQ。接口不用等 Broker 同步确认，失败还有 Outbox 重试兜底。
            mqOutboxService.saveAndSendAsync(
                    RocketMQConfig.TRAINING_CAMP_ORDER_TOPIC,
                    message,
                    objectMapper.writeValueAsString(message)
            );
        } catch (Exception e) {
            // 连 Outbox 都没写成功，说明后面的订单任务不会来了，要把 Redis 预扣资格还回去。
            rollbackRedisQualification(campId, userId);
            throw new BusinessException("报名请求繁忙，请稍后重试");
        }
    }

    // 查当前登录用户自己的训练营订单。
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
    // 支付订单：只能从待支付改成已支付，updated=0 说明状态已经被别人改过了。
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
    // 用户主动取消订单：只有待支付订单能取消，取消成功后才回补库存。
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
    // MQ 消费创建订单：幂等检查、扣 MySQL 真实库存、插入待支付订单都在这里。
    public void createOrderFromMessage(TrainingCampOrderMessage message) {
        // MQ 可能重复投递，先查已有订单做一次业务幂等，数据库唯一索引再兜底。
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
            // Redis 预扣成功但 MySQL 真实库存扣失败，要发补偿任务把 Redis 资格回滚掉。
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
    // 延迟消息触发的超时取消：订单过了支付时间还没付，就取消并回补库存。
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

    // 根据 id 查训练营，不存在就直接抛业务异常。
    private TrainingCamp getCamp(Long campId) {
        TrainingCamp camp = trainingCampMapper.selectById(campId);
        if (camp == null) {
            throw new BusinessException("训练营不存在");
        }
        return camp;
    }

    // 查订单并校验是不是当前登录用户自己的订单。
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

    // 校验报名时间窗口：没开始或已结束都不能报名。
    private void validateEnrollmentWindow(TrainingCamp camp) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(camp.getStartTime())) {
            throw new BusinessException("报名尚未开始");
        }
        if (now.isAfter(camp.getEndTime())) {
            throw new BusinessException("报名已结束");
        }
    }

    // 判断是否强制资格 token：后台开关优先，其次看开抢前后高峰窗口。
    private boolean isQualificationRequired(TrainingCamp camp) {
        if (Boolean.TRUE.equals(camp.getQualificationRequired())) {
            return true;
        }
        // 即使后台没手动开强校验，开抢前后这段高峰窗口也自动要求资格 token。
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime forceStart = camp.getStartTime().minus(QUALIFICATION_FORCE_BEFORE_START);
        LocalDateTime forceEnd = camp.getStartTime().plus(QUALIFICATION_FORCE_AFTER_START);
        return !now.isBefore(forceStart) && now.isBefore(forceEnd);
    }

    // 重置 Redis 报名缓存：写库存，同时清空已报名用户 Set。
    private void resetStockCache(TrainingCamp camp) {
        cacheStock(camp);
        redisTemplate.delete(buildUsersKey(camp.getId()));
    }

    // 把训练营库存写进 Redis，TTL 覆盖到活动结束后一小段时间。
    private void cacheStock(TrainingCamp camp) {
        Duration ttl = Duration.between(LocalDateTime.now(), camp.getEndTime().plusMinutes(10));
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(10);
        }
        redisTemplate.opsForValue().set(buildStockKey(camp.getId()), String.valueOf(camp.getStock()), ttl);
    }

    // Redis 没有库存 key 时补一次预热，避免 Lua 看到库存未初始化。
    private void ensureStockPreloaded(TrainingCamp camp) {
        String stockKey = buildStockKey(camp.getId());
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
            cacheStock(camp);
        }
    }

    // 扣 MySQL 真实库存：stock > 0 和 stock - 1 放同一条 UPDATE，防止超卖。
    private boolean reserveDbStock(Long campId) {
        // 判断库存和扣库存放在同一条 UPDATE 里，避免先查再扣导致并发超卖。
        int updated = trainingCampMapper.update(
                null,
                new LambdaUpdateWrapper<TrainingCamp>()
                        .eq(TrainingCamp::getId, campId)
                        .gt(TrainingCamp::getStock, 0)
                        .setSql("stock = stock - 1")
                        .set(TrainingCamp::getUpdateTime, LocalDateTime.now())
        );
        // updated 是 MySQL 影响行数：1 表示真实库存扣成功，0 表示库存已经不够。
        return updated > 0;
    }

    // 把待支付订单取消：只有状态真的从待支付改成已取消，才允许回补库存。
    private void cancelPendingOrder(TrainingCampOrder order) {
        // 条件更新保证只有“待支付 -> 已取消”的那一次请求能回补库存。
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
        // MySQL 库存先回补成功，再通过 Outbox + MQ 异步回补 Redis 库存和报名 Set。
        scheduleRedisQualificationRollback(order.getCampId(), order.getUserId());
    }

    // 记录一条 Redis 回补消息：MySQL 事务提交后再发 MQ，避免数据库没提交就改 Redis。
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

    // 回补 MySQL 真实库存。
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

    // Redis 回补 Consumer 调用的入口。
    public void rollbackRedisQualification(TrainingCampRedisRollbackMessage message) {
        rollbackRedisQualification(message.getCampId(), message.getUserId());
    }

    // 真正回补 Redis 资格：先 SREM 用户，删成功才 INCR 库存，防止重复加库存。
    private void rollbackRedisQualification(Long campId, Long userId) {
        redisTemplate.execute(
                ROLLBACK_REDIS_QUALIFICATION_SCRIPT,
                Arrays.asList(buildStockKey(campId), buildUsersKey(campId)),
                String.valueOf(userId)
        );
    }

    // 如果当前有事务，就等事务提交后再执行；没事务就直接执行。
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

    // 发送订单超时检查延迟消息，到时间后 Consumer 会判断是否需要取消。
    private void sendOrderTimeoutMessage(Long orderId) {
        MqOutboxMessage outboxMessage = mqOutboxService.savePendingDelay(
                RocketMQConfig.TRAINING_CAMP_ORDER_TIMEOUT_TOPIC,
                String.valueOf(orderId),
                (int) PAY_TIMEOUT_SECONDS
        );
        runAfterTransactionCommit(() -> mqOutboxService.sendNow(outboxMessage, orderId));
    }

    // 拼 Redis 库存 key。
    private String buildStockKey(Long campId) {
        return CAMP_STOCK_KEY + campId;
    }

    // 拼 Redis 已报名用户 Set key。
    private String buildUsersKey(Long campId) {
        return CAMP_USERS_KEY + campId;
    }

    // 拼报名资格 token key。
    private String buildQualificationKey(Long campId, String qualificationToken) {
        return CAMP_QUALIFICATION_KEY + campId + ":" + qualificationToken;
    }

    // 简单限流：同一用户同一训练营 10 秒最多操作 5 次。
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
