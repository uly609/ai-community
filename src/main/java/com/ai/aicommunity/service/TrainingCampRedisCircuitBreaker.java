package com.ai.aicommunity.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A small local circuit breaker that prevents a Redis outage from repeatedly
 * timing out the enrollment entrance and pushing traffic toward MySQL.
 */
@Component
public class TrainingCampRedisCircuitBreaker {

    private static final long OPEN_DURATION_MILLIS = 5_000L;

    private final AtomicLong openUntilMillis = new AtomicLong(0L);

    public boolean isOpen() {
        return System.currentTimeMillis() < openUntilMillis.get();
    }

    public void recordFailure() {
        openUntilMillis.set(System.currentTimeMillis() + OPEN_DURATION_MILLIS);
    }

    public void recordSuccess() {
        openUntilMillis.set(0L);
    }
}
