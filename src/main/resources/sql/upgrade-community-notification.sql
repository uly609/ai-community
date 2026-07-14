CREATE TABLE IF NOT EXISTS user_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_user_id BIGINT NOT NULL,
    sender_user_id BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    article_id BIGINT NOT NULL,
    comment_id BIGINT NOT NULL,
    content VARCHAR(500) NOT NULL,
    read_status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_notification (type, comment_id, recipient_user_id),
    KEY idx_recipient_read_create (recipient_user_id, read_status, create_time)
);
