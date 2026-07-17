# AI 知识社区后端系统

面向 AI 学习与技术交流场景的社区后端。项目提供用户登录、文章发布与浏览、点赞收藏评论、站内通知、文章 AI 摘要和训练营限时报名等能力；重点处理热点文章读取、缓存一致性和高并发报名下的库存与订单一致性。

> 这不是只接一个大模型接口的展示项目。AI 摘要被放入可靠异步链路：文章发布成功后先写 Outbox，再由 RocketMQ 消费并调用 OpenAI-compatible API，摘要写回后主动失效文章缓存。

## 技术栈

`Java 21` · `Spring Boot 3` · `MyBatis-Plus` · `MySQL` · `Redis` · `Caffeine` · `Guava BloomFilter` · `RocketMQ` · `Outbox` · `Canal` · `WebSocket` · `JWT` · `JMeter`

## 核心链路

```text
文章详情读取
Client -> BloomFilter -> Caffeine -> Redis -> MySQL
                         |              |
                    版本校验        逻辑过期 + 异步重建

文章发布与 AI 摘要
Client -> MySQL 事务 -> Outbox -> RocketMQ -> AI Summary Consumer
                                                  |
                                  OpenAI-compatible Chat API -> 写回摘要 -> 缓存失效

训练营报名
Client -> Redis Lua 预扣资格/库存 -> Outbox -> RocketMQ -> MySQL 创建订单
                                  \-> 失败或超时 -> 延迟消息 -> 补偿回滚
```

## 项目亮点

### 1. AI 摘要走可靠异步链路，而不是阻塞文章发布

- 文章创建完成后，通过 Outbox 落库并在事务提交后投递 `ARTICLE_AI_SUMMARY_TOPIC`。
- `ArticleAiSummaryConsumer` 异步调用 OpenAI-compatible Chat Completions API 生成中文摘要，摘要写回 MySQL 后主动失效文章详情缓存。
- AI 服务不可用时，文章主流程不被阻塞；未发送或失败的消息由 Outbox 重试任务补发。

### 2. 热点文章读取：BloomFilter -> Caffeine -> Redis -> MySQL

- BloomFilter 与 Redis 空值缓存拦截非法文章 ID，减少缓存穿透。
- Caffeine 承接本机热点读取，Redis 承接分布式缓存；缓存命中仍通过版本 key 校验，避免多实例下长期返回本机旧值。
- Redis 采用逻辑过期，命中过期热点数据时先返回旧值，再由 `SET NX EX` 抢锁异步回源重建，降低缓存击穿时的数据库压力。
- 文章更新采用 Cache Aside；缓存删除失败会进入补偿任务，Canal 监听 article 表 binlog 作为缓存失效兜底。

### 3. 高并发训练营报名：入口原子预扣，异步创建订单

- Redis Lua 在入口一次完成资格 Token 校验、库存预扣和用户幂等校验，避免超卖和重复报名。
- 报名订单通过 Outbox + RocketMQ 异步创建；消费者以 MySQL 联合唯一索引和条件更新实现幂等。
- 超时订单使用延迟消息取消，先回补 MySQL，再通过消息异步回补 Redis，保证最终一致性。

### 4. 社区通知与实时推送

- 评论、回复等通知通过 RocketMQ 解耦生成；数据库唯一约束抵御至少一次投递带来的重复消息。
- WebSocket 按用户维度维护会话连接，通知入库后实时推送给在线用户。

## 快速开始

### 1. 准备依赖

- JDK 21
- MySQL 8+
- Redis 6+
- RocketMQ NameServer / Broker

执行 `src/main/resources/sql/community-schema.sql` 初始化数据库；训练营相关表和升级脚本位于同目录。

### 2. 配置本地环境变量

```bash
export DB_URL='jdbc:mysql://localhost:3306/ai_community'
export DB_USERNAME='root'
export DB_PASSWORD='your-password'

# 启用文章 AI 摘要时再配置
export AI_API_KEY='your-api-key'
export AI_BASE_URL='https://api.openai.com/v1'
export AI_MODEL='gpt-4o-mini'
```

将 `app.ai.enabled` 设为 `true` 后，文章发布会进入异步 AI 摘要链路。

### 3. 启动

```bash
./mvnw spring-boot:run
```

## 项目结构

```text
src/main/java/com/ai/aicommunity/
├── controller/   # REST 接口
├── service/      # 文章、报名、AI 摘要、通知等核心业务
├── config/       # Redis、RocketMQ、WebSocket、Canal 配置
├── websocket/    # 通知握手与推送
├── mapper/       # MyBatis-Plus Mapper
└── entity/       # 领域实体与 Outbox 消息
```

## 说明

- `app.ai.enabled` 默认关闭，未配置 API Key 时不会发起模型调用。
- Canal 和 AI 摘要均为可选能力，主社区和报名链路可独立运行。
- 项目当前定位为后端工程与一致性方案的学习实践，压测时重点关注 RT、P95、QPS/TPS、错误率和最终数据一致性。

## 文档

- [代码与面试讲解](docs/interview-code-guide.md)
