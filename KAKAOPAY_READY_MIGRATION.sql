-- REVIEW-ONLY incremental MySQL 8 DDL, after the previous merchant/payment migrations.
-- Not executed against an existing/production database. Verify actual schema and audited legacy mappings first.
ALTER TABLE payment_attempt
    ADD COLUMN preparation_status ENUM('NONE','REQUESTING','PREPARED','UNKNOWN') NOT NULL DEFAULT 'NONE',
    ADD COLUMN preparation_claim VARCHAR(36) NULL,
    ADD COLUMN preparation_started_at DATETIME(6) NULL,
    ADD COLUMN prepared_tid VARCHAR(20) NULL,
    ADD COLUMN prepared_tid_hash VARCHAR(64) NULL,
    ADD COLUMN pg_user_reference VARCHAR(36) NULL,
    ADD COLUMN tax_free_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN callback_state_hash VARCHAR(64) NULL,
    ADD COLUMN callback_session_hash VARCHAR(64) NULL,
    ADD COLUMN callback_expires_at DATETIME(6) NULL,
    ADD COLUMN callback_consumed_at DATETIME(6) NULL,
    ADD COLUMN payment_pc_url VARCHAR(2048) NULL,
    ADD COLUMN payment_mobile_url VARCHAR(2048) NULL,
    ADD CONSTRAINT uk_attempt_prepared_tid UNIQUE (merchant_id,prepared_tid_hash);
-- No pg_token or Secret value is stored. The approved transaction ID is the same TID, not the AID.
-- Do not infer unknown legacy TIDs, partner_user_id or tax data from today's settings.
