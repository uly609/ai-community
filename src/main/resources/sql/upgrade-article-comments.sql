ALTER TABLE article
    ADD COLUMN comment_count INT NOT NULL DEFAULT 0 AFTER like_count;

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
