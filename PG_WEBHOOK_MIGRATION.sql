-- Review-only MySQL 8.0.16+ / InnoDB DDL. NOT applied to an existing server.
CREATE TABLE pg_webhook_inbox (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status ENUM('RECEIVED','PROCESSING','RETRY','DONE','FAILED') NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    failure VARCHAR(32) NULL,
    received_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_webhook_provider_event UNIQUE (provider,event_key_hash),
    INDEX ix_webhook_due (status,next_at,lease_until),
    CONSTRAINT ck_webhook_lease CHECK (attempts>=0 AND (
        (status='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    ))
) ENGINE=InnoDB;
-- attempt_id intentionally has no FK: an authenticated notification may arrive before its local reference is visible.
-- Provider/reference/financial claim are revalidated under parent and attempt locks when processing.
