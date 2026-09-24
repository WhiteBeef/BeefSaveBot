ALTER TABLE user_infos ADD COLUMN IF NOT EXISTS quality VARCHAR(16) NOT NULL DEFAULT 'HIGH';
ALTER TABLE user_infos ADD COLUMN IF NOT EXISTS output_format VARCHAR(16) NOT NULL DEFAULT 'MP4';
ALTER TABLE user_infos ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_infos ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

UPDATE user_infos u
SET last_seen_at = COALESCE((SELECT MAX(r.requested_at) FROM download_requests r WHERE r.user_id = u.id),
                            u.created_at)
WHERE last_seen_at IS NULL;

ALTER TABLE download_requests ADD COLUMN IF NOT EXISTS request_type VARCHAR(32) NOT NULL DEFAULT 'DOWNLOAD';
ALTER TABLE download_requests ADD COLUMN IF NOT EXISTS quality VARCHAR(16);
ALTER TABLE download_requests ADD COLUMN IF NOT EXISTS output_format VARCHAR(16);
ALTER TABLE download_requests ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE download_requests ADD COLUMN IF NOT EXISTS file_size BIGINT;

-- Разметка старых записей: раньше в url писался любой текст сообщения
UPDATE download_requests SET request_type = 'TRACK' WHERE url LIKE 'yandex-music-search:%';
UPDATE download_requests SET request_type = 'COMMAND' WHERE url LIKE '/%';
UPDATE download_requests SET request_type = 'SEARCH'
WHERE request_type = 'DOWNLOAD' AND url NOT LIKE 'http%';

CREATE INDEX IF NOT EXISTS idx_download_requests_requested_at ON download_requests (requested_at);
CREATE INDEX IF NOT EXISTS idx_download_requests_user_id ON download_requests (user_id);
CREATE INDEX IF NOT EXISTS idx_user_infos_username ON user_infos (LOWER(username));
