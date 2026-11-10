package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.*;
import java.util.UUID;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "pg_webhook_inbox", uniqueConstraints = @UniqueConstraint(name = "uk_webhook_merchant_event", columnNames = {"provider", "merchant_id", "event_key_hash"}),
        indexes = @Index(name = "ix_webhook_due", columnList = "status,next_at,lease_until"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(name = "ck_webhook_lease", constraints = "attempts >= 0 and ((status = 'PROCESSING' and lease_token is not null and lease_until is not null) or (status <> 'PROCESSING' and lease_token is null and lease_until is null))")
public class PgWebhookInbox {
    public enum Status { RECEIVED, PROCESSING, RETRY, DONE, FAILED }
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, updatable = false, length = 32) private String provider;
    @Column(name = "event_key_hash", nullable = false, updatable = false, length = 64) private String eventKeyHash;
    @Column(nullable = false, updatable = false, length = 36) private String attemptId;
    @Column(nullable=false,updatable=false,length=36) private String merchantId;
    @Column(updatable=false,length=200) private String pgTransactionId;
    @Column(nullable = false, updatable = false, length = 64) private String bodyHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(length = 36) private String leaseToken;
    private Instant leaseUntil;
    private Instant nextAt;
    @Column(nullable = false) private int attempts;
    @Column(length = 32) private String failure;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant processedAt;
    @Version private long version;
    public PgWebhookInbox(String provider,String merchantId, String eventKeyHash, String attemptId, String bodyHash,String transactionId, Instant now) {
        id = UUID.randomUUID().toString(); this.provider = provider; this.eventKeyHash = eventKeyHash;
        this.merchantId=merchantId;this.pgTransactionId=transactionId;
        // No delay for first delivery: DB timestamp rounding must not put a fresh event after the current Clock instant.
        this.attemptId = attemptId; this.bodyHash = bodyHash; receivedAt = now; updatedAt = now; status = Status.RECEIVED;
    }
    public boolean claim(Instant now, Duration lease, int maximum) {
        if (status == Status.DONE || status == Status.FAILED) return false;
        if (leaseUntil != null && leaseUntil.isAfter(now) || nextAt != null && nextAt.isAfter(now)) return false;
        if (attempts >= maximum) {
            status = Status.FAILED; failure = "ATTEMPTS_EXHAUSTED"; leaseToken = null; leaseUntil = null; updatedAt = now; return false;
        }
        attempts++; leaseToken = UUID.randomUUID().toString(); leaseUntil = now.plus(lease); status = Status.PROCESSING; updatedAt = now;
        return true;
    }
    public void requireLease(String token, Instant now) {
        if (status != Status.PROCESSING || token == null || !token.equals(leaseToken) || leaseUntil == null || !leaseUntil.isAfter(now))
            throw new IllegalStateException("Stale webhook lease");
    }
    public void complete(String token, Instant now) {
        requireLease(token, now); status = Status.DONE; processedAt = now; failure = null; clearLease(now);
    }
    public boolean retry(String token, Instant now, Duration backoff, int maximum, String code) {
        if (status != Status.PROCESSING || !java.util.Objects.equals(token, leaseToken) || leaseUntil == null || !leaseUntil.isAfter(now)) return false;
        status = attempts >= maximum ? Status.FAILED : Status.RETRY; failure = code; nextAt = now.plus(backoff); clearLease(now); return true;
    }
    private void clearLease(Instant now) { leaseToken = null; leaseUntil = null; updatedAt = now; }
}
