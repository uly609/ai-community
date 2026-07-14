# Load Test

## Article detail

JMeter file:

```text
dev/loadtest/article-detail.jmx
```

Current known result:

```text
100 threads, 1000 requests, 0% error, about 201 QPS, max 51 ms
```

## Training camp enroll

Start Redis, RocketMQ, and Spring Boot first.

Generate 100 user tokens:

```bash
cd "/Users/ntroi/Downloads/ai 知识社区/ai-community"
node dev/loadtest/generate-tokens.mjs
```

This creates:

```text
dev/loadtest/tokens.csv
```

Create a new training camp before each load test, for example:

```json
{
  "title": "AI 实战训练营压测场",
  "description": "JMeter 100 用户抢报名测试",
  "stock": 10,
  "startTime": "2026-07-13T00:00:00",
  "endTime": "2026-07-20T23:59:59"
}
```

Use the returned camp id in:

```text
dev/loadtest/training-camp-enroll.jmx
```

Update this variable in JMeter:

```text
campId = your new camp id
```

Expected result for stock 10 and 100 users:

```text
10 users succeed
90 users fail with no stock
no oversold stock
no duplicate order for one user
```

Check MySQL after the MQ consumer finishes:

```sql
SELECT status, COUNT(*)
FROM training_camp_order
WHERE camp_id = your_camp_id
GROUP BY status;

SELECT stock
FROM training_camp
WHERE id = your_camp_id;

SELECT COUNT(*) AS order_count,
       COUNT(DISTINCT user_id) AS user_count
FROM training_camp_order
WHERE camp_id = your_camp_id;
```

Check Redis:

```bash
redis-cli GET training:camp:stock:your_camp_id
redis-cli SCARD training:camp:users:your_camp_id
```
