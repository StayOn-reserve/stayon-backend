-- Review-only incremental MySQL 8 migration after PAYMENT_STATE_MIGRATION.sql.
-- Never executed against an existing database by this development task.
ALTER TABLE payment_attempt
    ADD COLUMN recovery_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recovery_lease_until DATETIME(6) NULL,
    ADD COLUMN recovery_next_at DATETIME(6) NULL,
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_failure VARCHAR(32) NULL,
    ADD COLUMN recovery_failed_at DATETIME(6) NULL,
    ADD INDEX ix_attempt_recovery_due (status,recovery_next_at,recovery_lease_until);
