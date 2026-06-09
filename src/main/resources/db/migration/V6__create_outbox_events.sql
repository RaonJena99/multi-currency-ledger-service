CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    dead_letter BOOLEAN NOT NULL DEFAULT FALSE
);

-- 성능 최적화를 위한 부분 복합 인덱스
CREATE INDEX idx_outbox_unprocessed 
    ON outbox_events(created_at) 
    WHERE processed = FALSE AND dead_letter = FALSE;