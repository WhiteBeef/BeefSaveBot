CREATE TABLE IF NOT EXISTS user_infos
(
    id          BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT NOT NULL UNIQUE,
    username    VARCHAR(255),
    first_name  VARCHAR(255),
    last_name   VARCHAR(255),
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS download_requests
(
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES user_infos (id),
    url          TEXT NOT NULL,
    requested_at TIMESTAMP DEFAULT NOW(),
    downloaded   BOOLEAN   DEFAULT FALSE
);
