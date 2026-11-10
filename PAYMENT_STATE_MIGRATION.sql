-- Review-only MySQL 8.0.16+ / InnoDB migration. NOT executed against an existing server.
-- Verify the current schema, backups and deployment ddl-auto settings before applying elsewhere.
ALTER TABLE service_order MODIFY COLUMN status ENUM(
    'PENDING_PAYMENT','PAID','CANCELLED','EXPIRED','REFUNDED','PAYMENT_PROCESSING','CANCEL_PENDING'
) NOT NULL;

CREATE TABLE payment_attempt (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status ENUM('READY','APPROVING','APPROVED','FAILED','UNKNOWN','CANCEL_PENDING','CANCELED') NOT NULL,
    active_order_id BIGINT NULL,
    approved_order_id BIGINT NULL,
    pg_transaction_id VARCHAR(200) NULL,
    pg_transaction_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    approval_claim VARCHAR(36) NULL,
    cancel_claim VARCHAR(36) NULL,
    cancel_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    approval_started_at DATETIME(6) NULL,
    approval_finished_at DATETIME(6) NULL,
    approved_at DATETIME(6) NULL,
    cancel_requested_at DATETIME(6) NULL,
    cancel_started_at DATETIME(6) NULL,
    canceled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_attempt_order FOREIGN KEY (order_id) REFERENCES service_order(id),
    CONSTRAINT uk_attempt_order_key UNIQUE (order_id,idempotency_key_hash),
    CONSTRAINT uk_attempt_active_order UNIQUE (active_order_id),
    CONSTRAINT uk_attempt_approved_order UNIQUE (approved_order_id),
    CONSTRAINT uk_attempt_provider_tx UNIQUE (provider,pg_transaction_hash),
    INDEX ix_attempt_recovery (status,updated_at),
    CONSTRAINT ck_attempt_slots CHECK (
        (active_order_id IS NULL OR active_order_id=order_id)
        AND (approved_order_id IS NULL OR approved_order_id=order_id)
        AND ((status IN ('READY','APPROVING','UNKNOWN','APPROVED','CANCEL_PENDING') AND active_order_id IS NOT NULL)
          OR (status='FAILED' AND active_order_id IS NULL)
          OR (status='CANCELED' AND ((approved_order_id IS NULL AND active_order_id IS NULL)
               OR (approved_order_id IS NOT NULL AND active_order_id IS NOT NULL))))
        AND (status NOT IN ('APPROVED','CANCEL_PENDING') OR approved_order_id IS NOT NULL)
        AND ((approved_order_id IS NULL AND pg_transaction_hash IS NULL AND pg_transaction_id IS NULL)
          OR (approved_order_id IS NOT NULL AND pg_transaction_hash IS NOT NULL AND pg_transaction_id IS NOT NULL))
        AND (status<>'FAILED' OR approved_order_id IS NULL)
        AND amount>=0
    )
) ENGINE=InnoDB;
