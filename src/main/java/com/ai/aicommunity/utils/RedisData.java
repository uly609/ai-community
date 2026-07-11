package com.ai.aicommunity.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis逻辑过期缓存包装对象。
 * 数据本身不过期，通过expireTime判断是否需要异步刷新。
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
