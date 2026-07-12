CREATE TABLE IF NOT EXISTS training_camp (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    stock INT NOT NULL,
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
