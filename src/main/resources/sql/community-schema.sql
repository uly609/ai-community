CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    UNIQUE KEY uk_username (username)
);

CREATE TABLE IF NOT EXISTS article (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    ai_summary VARCHAR(1000),
    view_count INT NOT NULL DEFAULT 0,
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_user_id (user_id),
    KEY idx_create_time (create_time)
);

CREATE TABLE IF NOT EXISTS article_like (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_article_user (article_id, user_id),
    KEY idx_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS article_favorite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_article_user (article_id, user_id),
    KEY idx_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS article_comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    reply_user_id BIGINT,
    content VARCHAR(500) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_article_parent_create (article_id, parent_id, create_time),
    KEY idx_user_id (user_id)
);

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

CREATE TABLE IF NOT EXISTS training_camp (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    stock INT NOT NULL,
    qualification_required TINYINT(1) NOT NULL DEFAULT 0,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS training_camp_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    camp_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status INT NOT NULL,
    pay_expire_time DATETIME,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_camp_user (camp_id, user_id),
    KEY idx_user_id (user_id),
    KEY idx_camp_id (camp_id)
);

CREATE TABLE IF NOT EXISTS mq_outbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status INT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    delay_seconds INT,
    next_retry_time DATETIME NOT NULL,
    error_message VARCHAR(500),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_status_retry_time (status, next_retry_time)
);

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

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sender_user_id BIGINT NOT NULL,
    receiver_user_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    read_status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_sender_receiver_create (sender_user_id, receiver_user_id, create_time),
    KEY idx_receiver_read_create (receiver_user_id, read_status, create_time)
);

CREATE TABLE IF NOT EXISTS community_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    size BIGINT NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    create_time DATETIME NOT NULL,
    KEY idx_user_create (user_id, create_time)
);

CREATE TABLE IF NOT EXISTS learning_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_user_update (user_id, update_time)
);

CREATE TABLE IF NOT EXISTS learning_conversation_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    create_time DATETIME NOT NULL,
    KEY idx_conversation_create (conversation_id, create_time)
);
