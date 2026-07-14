# AI Community Pressure Test Record

Test environment: local macOS development machine, Spring Boot on `localhost:8080`, Redis, RocketMQ, and MySQL all running locally. Results are useful for demonstrating the design and comparing later optimizations; they are not production-capacity claims.

## Article Detail Read Test

| Item | Result |
| --- | --- |
| Target | `GET /articles/{articleId}` |
| Tool | Apache JMeter |
| Concurrent setup | 100 threads, 5-second ramp-up, 10 loops each |
| Total requests | 1000 |
| Business-success requests | 1000 |
| Error rate | 0.00% |
| Average RT | 7.64 ms |
| P95 RT | 14 ms |
| Max RT | 283 ms |
| Throughput / QPS | 209.64 requests/sec |
| Effective TPS | 209.64 successful requests/sec |

JMeter asserts that the response body business code is `200`; HTTP success alone is not enough. This test validates the article detail read path with local cache, Redis cache, Bloom filter, logical expiration, and asynchronous refresh.

## Training Camp Enrollment Test

| Item | Result |
| --- | --- |
| Target | `POST /training-camps/{campId}/enroll` |
| Concurrent setup | 100 threads, 1-second ramp-up, 1 request each |
| Users | 100 distinct logged-in users from `tokens.csv` |
| Initial camp stock | 100 |
| Total requests | 100 |
| Business-success requests | 100 |
| Error rate | 0.00% |
| Average RT | 5313.37 ms |
| P95 RT | 6430 ms |
| Max RT | 6557 ms |
| Throughput / QPS | 15.16 requests/sec |
| Effective TPS | 15.16 successful qualification requests/sec |
| Database verification | 100 pending orders, 100 distinct users, final stock `0` |

The plan asserts both `"code":200` and `抢报名成功`. Therefore a response such as `名额已抢完`, `重复报名`, or `报名尚未开始` is counted as a failed sample, even though this project's unified response currently returns HTTP `200` for business errors.

`Effective TPS` here means the rate at which the entrance successfully grants a qualification. Order creation runs asynchronously through RocketMQ; the database verification is the separate proof that those accepted qualifications eventually became orders.

## Qualification Token And Outbox A/B Test

The enrollment entrance was split into three observable paths: direct requests without a qualification token, qualification-token issuance, and token-carrying enrollment. The legitimate enrollment A/B test used the same local machine, 1000 distinct users, 1000 camp slots, the same CSV token source, and a 5-second ramp-up for both variants.

| Scenario | Requests | Average RT | P95 RT | Throughput | Business verification |
| --- | ---: | ---: | ---: | ---: | --- |
| No-token direct enrollment | 1000 | 25.48 ms | 176 ms | 818.33 req/s | All requests were rejected at the admission entrance |
| Apply qualification token | 1000 | 8.17 ms | 44 ms | 207.00 req/s | 1000 tokens issued |
| A: synchronous RocketMQ send, token enrollment | 1000 | 20.39 ms | 103 ms | 212.59 req/s | 1000 orders, 1000 users, stock 0, 1000 Outbox messages sent |
| B: asynchronous RocketMQ first send, token enrollment | 1000 | 14.46 ms | 78 ms | 215.19 req/s | 1000 orders, 1000 users, stock 0, 1000 Outbox messages sent |

Conclusion: persisting the Outbox record before returning is the durability boundary. Moving only the first RocketMQ broker acknowledgement off the request thread reduced entrance average RT by about 29.1% and P95 RT by about 24.3% in this single same-condition comparison, while preserving the final order, inventory, and Outbox consistency checks.

This is a one-run A/B result on one local development machine, not a production capacity claim or a three-run median. It proves the optimization direction and its local effect; a production-style benchmark should repeat each variant and collect machine, Redis, RocketMQ, and MySQL metrics.

```sql
SELECT status, COUNT(*) AS count
FROM training_camp_order
WHERE camp_id = :campId
GROUP BY status;

SELECT stock
FROM training_camp
WHERE id = :campId;

SELECT COUNT(*) AS order_count,
       COUNT(DISTINCT user_id) AS user_count
FROM training_camp_order
WHERE camp_id = :campId;
```

## Interview Summary

For the training camp limited-enrollment flow, Redis Lua atomically completes stock pre-deduction and one-user-one-camp qualification. RocketMQ asynchronously queues order creation to smooth write spikes. The order consumer uses controlled concurrency, idempotent order checks, and a database unique constraint. MySQL stock deduction and order creation run in one transaction; cancellation, database stock restoration, and the Redis-restoration Outbox record also run in one transaction. Outbox retries message delivery, and the Redis rollback consumer executes an atomic Lua restoration, giving the flow eventual consistency across MySQL, RocketMQ, and Redis.
