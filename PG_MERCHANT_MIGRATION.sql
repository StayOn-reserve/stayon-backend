-- Incremental REVIEW-ONLY MySQL 8.0.16+ / InnoDB DDL after the existing payment/inbox migrations.
-- NOT executed against an existing database. Verify actual table/column names, types and collations first.
CREATE TABLE pg_merchant (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment ENUM('SANDBOX','LIVE') NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    external_id_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active BOOLEAN NOT NULL,
    active_owner_id BIGINT NULL,
    secret_reference VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_merchant_owner FOREIGN KEY (owner_id) REFERENCES user(user_id),
    CONSTRAINT uk_merchant_provider_identity UNIQUE (provider,environment,external_id_hash),
    CONSTRAINT uk_merchant_active_owner UNIQUE (provider,environment,active_owner_id),
    CONSTRAINT uk_merchant_id_provider UNIQUE (id,provider),
    CONSTRAINT ck_merchant_owner CHECK (
        (active=true AND active_owner_id=owner_id AND active_owner_id IS NOT NULL)
        OR (active=false AND active_owner_id IS NULL)
    )
) ENGINE=InnoDB;
CREATE TABLE pg_merchant_currency (
    merchant_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (merchant_id,currency),
    CONSTRAINT fk_merchant_currency FOREIGN KEY (merchant_id) REFERENCES pg_merchant(id)
) ENGINE=InnoDB;

-- Legacy attempts remain NULL until an operator maps each historical transaction from authoritative records.
-- Never infer historical merchants from today's active Site/owner configuration.
ALTER TABLE payment_attempt
    ADD COLUMN merchant_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY COLUMN provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    DROP INDEX uk_attempt_provider_tx,
    ADD CONSTRAINT uk_attempt_merchant_tx UNIQUE (merchant_id,pg_transaction_hash),
    ADD CONSTRAINT fk_attempt_merchant FOREIGN KEY (merchant_id,provider) REFERENCES pg_merchant(id,provider);

-- Pause ingress/workers during audited legacy Inbox mapping. NULL legacy rows cannot be processed automatically.
-- Map merchant_id/PG transaction evidence from authenticated historical records, then enforce NOT NULL.
ALTER TABLE pg_webhook_inbox
    ADD COLUMN merchant_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN pg_transaction_id VARCHAR(200) NULL;
-- REQUIRED MANUAL MAPPING CHECKPOINT: do not continue with unmapped rows.
-- ALTER TABLE pg_webhook_inbox MODIFY COLUMN merchant_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
-- After the mapping checkpoint, replace the old index:
-- ALTER TABLE pg_webhook_inbox DROP INDEX uk_webhook_provider_event,
--   ADD CONSTRAINT uk_webhook_merchant_event UNIQUE (provider,merchant_id,event_key_hash);
