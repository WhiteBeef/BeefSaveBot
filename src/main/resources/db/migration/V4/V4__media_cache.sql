-- Уже отправленные в Telegram файлы: повторный запрос отдаётся по file_id без скачивания
CREATE TABLE IF NOT EXISTS media_cache
(
    cache_key  VARCHAR(1024) PRIMARY KEY,
    file_id    VARCHAR(255) NOT NULL,
    media_kind VARCHAR(16)  NOT NULL,
    file_size  BIGINT,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_media_cache_created_at ON media_cache (created_at);
