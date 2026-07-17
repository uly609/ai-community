# AI 知识社区：面试代码展示顺序

面试展示时不要从 Controller 一路翻到底。按下面顺序讲：先业务，再性能，再可靠性。
每一类只打开列出的关键文件，讲清楚“问题、方案、结果”。

## 1. 项目入口与登录鉴权

1. `controller/UserController.java`：注册、登录接口。
2. `utils/JwtUtil.java`：生成和解析 JWT。
3. `interceptor/LoginUserInterceptor.java`：校验受保护请求的 Token。
4. `utils/UserHolder.java`：将当前用户 ID 放到 ThreadLocal。
5. `config/WebConfig.java`：配置哪些接口不需要登录。

讲法：用户登录拿到 JWT；后续请求经过拦截器，当前用户 ID 存入
`UserHolder`，文章、评论、报名等业务都据此做权限和归属校验。

## 2. 文章核心业务

1. `controller/ArticleController.java`：文章接口。
2. `service/ArticleService.java`：发布、详情、更新、删除、作者校验。
3. `entity/Article.java`、`mapper/ArticleMapper.java`：数据模型与持久化。

讲法：文章是读多写少的展示数据。更新时先更新 MySQL，再删除缓存，
下一次读取再加载新数据。

## 3. 文章高并发读取与缓存

按下面顺序打开 `ArticleService`，不要一次展示整份文件。

1. `config/CaffeineConfig.java`：一级 Caffeine 本地缓存，最多 1000 条，
   写入后 10 分钟过期。
2. `ArticleService.detail()`：读取顺序为 `Caffeine -> Redis -> MySQL`。
3. `config/ArticleBloomFilterInitializer.java`、`utils/BloomFilterUtil.java`：
   启动时加载文章 ID，拦截明显不存在的 ID。
4. `ArticleService.handleRedisData()`：逻辑过期、Redis 刷新锁、异步重建。
5. `ArticleService.RELEASE_LOCK_SCRIPT`、`releaseLock()`：UUID 锁值加 Lua
   比较删除，避免过期旧任务误删新锁。
6. `ArticleService.invalidateCache()`：文章变化时同时删除本地和 Redis 缓存。

讲法：

- 布隆过滤器和空值缓存处理缓存穿透。
- 逻辑过期加 Redis 锁解决热点缓存击穿：一个请求异步重建，其余请求先返回旧文章。
- Caffeine 降低 Redis 的高并发访问压力；Redis 降低 MySQL 压力。
- 文章缓存接受短暂最终一致性；当前项目用 `article:version:{id}` 做版本校验，
  用 `cache_invalidation_task` 做缓存删除失败后的补偿重试。

如果面试官追问“文章缓存一致性生产上怎么做”，可以补充 Canal/binlog 方案：

- 文章这种读多写少业务，核心是 MySQL 变更后让 Redis/Caffeine 失效。
- 常规 Cache Aside 是业务代码里 `更新 MySQL -> 删除缓存`；如果删除失败，
  要记录补偿任务定时重试。
- 当前项目已经预留 `ArticleCanalCacheInvalidationConsumer`，默认关闭；启动 Canal Server
  后把 `app.canal.article-cache.enabled=true` 打开即可监听 `article` 表。
- Canal 监听 MySQL binlog 的意义是：只要 MySQL 事务提交，binlog 就会记录这次
  `update/delete`，Canal 能根据 binlog 统一生成缓存失效事件。
- Canal 不是保证 Redis 一定删除成功。它解决的是“数据库变更事件可靠捕获”；
  Redis 删除失败仍然要靠 MQ 重试、补偿任务、死信或告警兜底。
- 面试话术：文章缓存可以用 Canal 监听 binlog 做统一缓存失效；训练营报名不用
  Canal 做主链路，因为报名需要在请求进入 MySQL 前削峰，所以更适合
  Redis Lua + Outbox + RocketMQ。

## 4. 点赞、收藏、评论与通知

1. `service/ArticleInteractionService.java`：点赞、收藏，Redis Set 判断用户状态，
   ZSet 维护热门文章；MySQL 唯一索引兜底重复操作。
2. `service/ArticleCommentService.java`：评论、回复、评论数维护、批量查询用户，
   避免 N+1 查询。
3. `service/NotificationService.java`：站内通知查询和已读。
4. `service/CommunityNotificationConsumer.java`：异步消费评论通知。
5. `service/MqOutboxService.java`：可靠通知投递。
6. `config/WebSocketConfig.java`、`websocket/NotificationWebSocketHandshakeInterceptor.java`、
   `websocket/NotificationWebSocketHandler.java`：在线用户的 JWT 握手鉴权和实时通知推送。

讲法：评论同步落库；通知异步发送，通知失败不阻塞评论发布。通知持久化提交后，
若接收者在线，会通过 WebSocket 实时推送；离线用户仍可通过通知查询接口获取记录。

## 5. AI 摘要异步链路

1. `config/AiProperties.java`：AI 配置抽象。
2. `ArticleService.enqueueAiSummaryAfterCommit()`：文章发布后创建 AI 摘要 Outbox 任务。
3. `service/ArticleAiSummaryConsumer.java`：摘要消息消费者。
4. `service/ArticleAiSummaryService.java`：调用 OpenAI 兼容 API，更新摘要并失效文章缓存。

讲法：文章发布不等待 AI；AI 摘要是异步增强能力，失败不影响文章主业务。

## 6. 训练营高并发报名主链路

这一部分是高并发重点，严格按顺序讲。

1. `controller/TrainingCampController.java`：报名、订单、支付、取消接口。
2. `TrainingCampService.enroll()`：校验登录、时间窗口、熔断状态、限流，之后执行 Lua。
3. `TrainingCampService.SECKILL_SCRIPT`：原子校验库存和重复报名，Redis 库存减一，
   用户加入报名 Set。
4. `MqOutboxService.saveAndSend()`：先记录 Outbox，再发报名消息到 RocketMQ。
5. `TrainingCampOrderConsumer.java`：Consumer 并发数和消息重试次数。
6. `TrainingCampService.createOrderFromMessage()`：订单幂等校验、真实库存扣减、创建待支付订单。
7. `TrainingCampService.reserveDbStock()`：对应 SQL：
   `UPDATE ... SET stock = stock - 1 WHERE stock > 0`。
8. `entity/TrainingCampOrder.java`、`sql/training-camp.sql`：订单表和
   `(camp_id, user_id)` 联合唯一约束。

讲法：Redis Lua 用于高并发资格预扣，RocketMQ 削峰，MySQL 是最终库存和订单账本。
Redis Lua 是 Redis 内部原子执行，不是跨 Redis 与 MySQL 的分布式事务。

## 7. 可靠消息、超时取消、补偿与幂等

在主报名链路讲清后，再展示这一部分。

1. `entity/MqOutboxMessage.java`、`sql/upgrade-mq-outbox.sql`：Outbox 表结构和状态。
2. `MqOutboxService.retryPendingMessages()`：应用恢复后扫描待发送、失败消息并重试。
3. `TrainingCampService.sendOrderTimeoutMessage()`：发送订单超时检查延迟消息。
4. `TrainingCampOrderTimeoutConsumer.java`：延迟消息消费者。
5. `TrainingCampService.cancelPendingOrder()`：条件更新
   `PENDING -> CANCELED`，回补 MySQL 库存，并在同一事务中写 Redis 回补 Outbox。
6. `TrainingCampRedisRollbackConsumer.java`：消费 Redis 回补消息。
7. `TrainingCampService.ROLLBACK_REDIS_QUALIFICATION_SCRIPT`：先 `SREM` 用户，
   只有删除成功才 `INCR` Redis 库存，保证重复消息不会重复回补。
8. `TrainingCampService.pay()`：`PENDING -> PAID` 条件更新与 `updated` 行数判断。

讲法：

- Outbox 解决“数据库事实已提交，但应用还没发 MQ 就宕机”的消息丢失问题。
- `UPDATE ... WHERE status = PENDING` 防止支付、取消、超时任务互相覆盖状态。
- Redis Lua 回补保证 MQ 重复投递时库存只加一次。
- 这是最终一致性与补偿，不是强一致分布式事务。

## 8. 故障保护与测试证据

1. `TrainingCampRedisCircuitBreaker.java`：Redis 异常后打开 5 秒本地熔断窗口。
2. `TrainingCampService.enroll()`：捕获 `DataAccessException` 后记录失败；熔断打开时
   直接拒绝报名，不让流量绕过 Redis 冲到 MySQL。
3. `test/java/com/ai/aicommunity/service/TrainingCampServiceTest.java`：重复消息、
   已取消订单、Outbox 回补、熔断开启等测试。
4. `dev/loadtest/article-detail.jmx`、`dev/loadtest/training-camp-enroll.jmx`：JMeter 脚本。
5. `dev/loadtest/pressure-test-report.md`：压测结果记录。

讲法：当前熔断是单机应用级；生产多实例通常还会加网关限流、集中式熔断、死信队列告警、
监控和数据对账。
