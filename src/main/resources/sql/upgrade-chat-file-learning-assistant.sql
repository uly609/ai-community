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
