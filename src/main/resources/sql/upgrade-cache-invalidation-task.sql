CREATE TABLE IF NOT EXISTS cache_invalidation_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cache_type VARCHAR(50) NOT NULL,
    biz_id BIGINT NOT NULL,
    status INT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    error_message VARCHAR(500),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_status_retry_time (status, next_retry_time),
    KEY idx_cache_biz (cache_type, biz_id)
);
