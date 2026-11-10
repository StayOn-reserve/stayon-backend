-- Manual migration for an existing MySQL 8 / InnoDB schema. Never auto-executed.
-- Preserve legacy orders: NULL keys do not collide in the unique index.
ALTER TABLE service_order
    ADD COLUMN idempotency_key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uk_order_reservation_idempotency UNIQUE (reservation_id, idempotency_key_hash);
