package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cache_invalidation_task")
public class CacheInvalidationTask {

    private Long id;

    private String cacheType;

    private Long bizId;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
