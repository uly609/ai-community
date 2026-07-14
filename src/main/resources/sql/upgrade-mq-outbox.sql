CREATE TABLE IF NOT EXISTS mq_outbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status INT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    error_message VARCHAR(500),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_status_retry_time (status, next_retry_time)
);
