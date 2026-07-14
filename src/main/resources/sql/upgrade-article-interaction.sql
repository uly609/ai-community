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
