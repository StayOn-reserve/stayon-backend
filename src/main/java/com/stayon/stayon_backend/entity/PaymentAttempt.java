package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Entity
@Table(name = "payment_attempt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_attempt_order_key", columnNames = {"order_id", "idempotency_key_hash"}),
        @UniqueConstraint(name = "uk_attempt_active_order", columnNames = "active_order_id"),
        @UniqueConstraint(name = "uk_attempt_approved_order", columnNames = "approved_order_id"),
        @UniqueConstraint(name = "uk_attempt_merchant_tx", columnNames = {"merchant_id", "pg_transaction_hash"}),
        @UniqueConstraint(name = "uk_attempt_prepared_tid",columnNames={"merchant_id","prepared_tid_hash"})
}, indexes = { @Index(name = "ix_attempt_recovery", columnList = "status,updated_at"),
        @Index(name = "ix_attempt_recovery_due", columnList = "status,recovery_next_at,recovery_lease_until") })
@Check(name = "ck_attempt_slots", constraints = """
        (active_order_id is null or active_order_id = order_id)
        and (approved_order_id is null or approved_order_id = order_id)
        and ((status in ('READY','APPROVING','UNKNOWN','APPROVED','CANCEL_PENDING') and active_order_id is not null)
          or (status = 'FAILED' and active_order_id is null)
          or (status = 'CANCELED' and ((approved_order_id is null and active_order_id is null)
              or (approved_order_id is not null and active_order_id is not null))))
        and (status not in ('APPROVED','CANCEL_PENDING') or approved_order_id is not null)
        and ((approved_order_id is null and pg_transaction_hash is null and pg_transaction_id is null)
          or (approved_order_id is not null and pg_transaction_hash is not null and pg_transaction_id is not null))
        and (status <> 'FAILED' or approved_order_id is null)
        and amount >= 0
        """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {
    public enum Status { READY, APPROVING, APPROVED, FAILED, UNKNOWN, CANCEL_PENDING, CANCELED }
    public enum RecoveryKind { APPROVAL_LOOKUP, CANCEL_DISPATCH, CANCEL_LOOKUP }
    public enum PreparationStatus { NONE, REQUESTING, PREPARED, UNKNOWN }

    @Id @Column(length = 36) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false) private ServiceOrder serviceOrder;
    @Column(name="merchant_id",length=36,updatable=false) private String merchantId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumns({
        @JoinColumn(name="merchant_id",referencedColumnName="id",insertable=false,updatable=false),
        @JoinColumn(name="provider",referencedColumnName="provider",insertable=false,updatable=false)}) private PgMerchant merchant;
    @Transient private boolean persisted;
    @PostLoad @PostPersist private void freezeIdentity(){persisted=true;}
    @Column(nullable = false, updatable = false, length = 32) private String provider;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64) private String idempotencyKeyHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Status status;
    @Column(name = "active_order_id") private Long activeOrderId;
    @Column(name = "approved_order_id") private Long approvedOrderId;
    @Column(name = "pg_transaction_id", length = 200) private String pgTransactionId;
    @Column(name = "pg_transaction_hash", length = 64) private String pgTransactionHash;
    @Column(length = 36) private String approvalClaim;
    @Column(length = 36) private String cancelClaim;
    private boolean cancelUnknown;
    private Instant approvalStartedAt;
    private Instant approvalFinishedAt;
    private Instant approvedAt;
    private Instant cancelRequestedAt;
    private Instant cancelStartedAt;
    private Instant canceledAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @Column(length = 36) private String recoveryToken;
    private Instant recoveryLeaseUntil;
    private Instant recoveryNextAt;
    @Column(nullable = false) private int recoveryAttempts;
    @Column(length = 32) private String recoveryFailure;
    private Instant recoveryFailedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private PreparationStatus preparationStatus=PreparationStatus.NONE;
    @Column(length=36) private String preparationClaim;
    private Instant preparationStartedAt;
    @Column(length=20) private String preparedTid;
    @Column(length=64) private String preparedTidHash;
    @Column(length=36) private String pgUserReference;
    private long taxFreeAmount;
    @Column(length=64) private String callbackStateHash;
    @Column(length=64) private String callbackSessionHash;
    private Instant callbackExpiresAt;
    private Instant callbackConsumedAt;
    @Column(length=2048) private String paymentPcUrl;
    @Column(length=2048) private String paymentMobileUrl;

    public PaymentAttempt(ServiceOrder order, String provider, String keyHash, Instant now) {
        if (order == null || order.getId() == null || !validProvider(provider) || keyHash == null || !keyHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid payment attempt");
        id = UUID.randomUUID().toString(); serviceOrder = order; this.provider = provider;
        amount = order.getTotalAmount(); currency = "KRW"; idempotencyKeyHash = keyHash;
        status = Status.READY; activeOrderId = order.getId(); createdAt = now; updatedAt = now;
    }

    public static boolean validProvider(String provider) { return provider != null && provider.matches("[A-Z][A-Z0-9_]{1,31}"); }
    public void pinMerchant(PgMerchant merchant){
        if(persisted||this.merchant!=null||status!=Status.READY||merchant==null||!provider.equals(merchant.getProvider()))throw new IllegalStateException("Invalid merchant pin");
        this.merchant=merchant;this.merchantId=merchant.getId();
    }
    public boolean claimPreparation(String stateHash,String sessionHash,Instant expiresAt,long taxFree,Instant now){
        if(status!=Status.READY)throw new IllegalStateException("Payment cannot be prepared");
        if(preparationStatus!=PreparationStatus.NONE)return false;
        if(!provider.equals("KAKAOPAY")||taxFree<0||taxFree>amount)throw new IllegalArgumentException("Invalid preparation");
        preparationStatus=PreparationStatus.REQUESTING;preparationClaim=UUID.randomUUID().toString();preparationStartedAt=now;
        callbackStateHash=stateHash;callbackSessionHash=sessionHash;callbackExpiresAt=expiresAt;taxFreeAmount=taxFree;
        pgUserReference=UUID.randomUUID().toString();updatedAt=now;return true;
    }
    public void preparationUnknown(String claim,Instant now){
        if(!java.util.Objects.equals(claim,preparationClaim))throw new IllegalStateException("Preparation claim mismatch");
        if(preparationStatus==PreparationStatus.REQUESTING){preparationStatus=PreparationStatus.UNKNOWN;updatedAt=now;}
    }
    public void prepared(String claim,String tid,String pc,String mobile,Instant now){
        if(!java.util.Objects.equals(claim,preparationClaim))throw new IllegalStateException("Preparation claim mismatch");
        if(preparationStatus==PreparationStatus.PREPARED){if(!preparedTid.equals(tid))throw new IllegalStateException("Conflicting TID");return;}
        if(preparationStatus!=PreparationStatus.REQUESTING||tid==null||tid.length()!=20)throw new IllegalStateException("Invalid preparation result");
        preparedTid=tid;preparedTidHash=digest(tid);paymentPcUrl=pc;paymentMobileUrl=mobile;preparationStatus=PreparationStatus.PREPARED;updatedAt=now;
    }
    public void consumeCallback(String state,String sessionHash,Instant now){
        if(preparationStatus!=PreparationStatus.PREPARED||callbackConsumedAt!=null||callbackExpiresAt==null||!callbackExpiresAt.isAfter(now)
                ||!java.util.Objects.equals(callbackSessionHash,sessionHash)||state==null
                ||!MessageDigest.isEqual(callbackStateHash.getBytes(StandardCharsets.US_ASCII),digest(state).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalStateException("Invalid or expired authentication context");
        callbackConsumedAt=now;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public boolean claimApproval(Instant now) {
        if (status != Status.READY) return false;
        approvalClaim = UUID.randomUUID().toString(); status = Status.APPROVING; approvalStartedAt = now; updatedAt = now;
        return true;
    }
    public void unknown(String claim, Instant now) {
        verifyApprovalClaim(claim);
        if (status == Status.APPROVING || status == Status.UNKNOWN) { status = Status.UNKNOWN; updatedAt = now; }
    }
    public boolean approve(String claim, String transactionId, Instant now) {
        verifyApprovalClaim(claim);
        if (transactionId == null || transactionId.isBlank() || transactionId.length() > 200) throw new IllegalArgumentException("Invalid PG transaction identity");
        if (status == Status.APPROVED || status == Status.CANCEL_PENDING || status == Status.CANCELED) {
            if (!transactionId.equals(pgTransactionId)) throw new IllegalStateException("Conflicting approval result");
            return false;
        }
        if (status != Status.APPROVING && status != Status.UNKNOWN) throw new IllegalStateException("Approval is terminal");
        pgTransactionId = transactionId; pgTransactionHash = digest(transactionId); approvedOrderId = serviceOrder.getId();
        status = cancelRequestedAt == null ? Status.APPROVED : Status.CANCEL_PENDING;
        if (status == Status.CANCEL_PENDING) { recoveryAttempts = 0; recoveryNextAt = null; }
        approvedAt = now; approvalFinishedAt = now; updatedAt = now; return true;
    }
    public boolean decline(String claim, Instant now) {
        verifyApprovalClaim(claim);
        if (status == Status.FAILED) return false;
        if (status != Status.APPROVING && status != Status.UNKNOWN) throw new IllegalStateException("Conflicting decline result");
        status = Status.FAILED; activeOrderId = null; approvalFinishedAt = now; updatedAt = now; return true;
    }
    public boolean requestCancel(Instant now) {
        if (cancelRequestedAt != null) return false;
        if (status != Status.READY && status != Status.APPROVING && status != Status.UNKNOWN && status != Status.CANCEL_PENDING)
            throw new IllegalStateException("Cancellation cannot be requested");
        cancelRequestedAt = now; updatedAt = now;
        if (status == Status.READY) { status = Status.CANCELED; activeOrderId = null; canceledAt = now; }
        return true;
    }
    public boolean claimCancel(Instant now) {
        if (status != Status.CANCEL_PENDING || cancelClaim != null || cancelUnknown) return false;
        cancelClaim = UUID.randomUUID().toString(); cancelStartedAt = now; updatedAt = now; return true;
    }
    public void cancellationUnknown(String claim, Instant now) {
        verifyCancelClaim(claim); if (status == Status.CANCELED) return;
        if (status != Status.CANCEL_PENDING) throw new IllegalStateException("Invalid cancellation state");
        cancelUnknown = true; updatedAt = now;
    }
    public boolean canceled(String claim, Instant now) {
        verifyCancelClaim(claim); if (status == Status.CANCELED) return false;
        if (status != Status.CANCEL_PENDING) throw new IllegalStateException("Invalid cancellation state");
        // Retain the order slot after a charged attempt is voided: this order must never be charged again.
        status = Status.CANCELED; canceledAt = now; updatedAt = now; cancelUnknown = false; return true;
    }
    private void verifyApprovalClaim(String claim) {
        if (approvalClaim == null || !approvalClaim.equals(claim)) throw new IllegalStateException("Approval claim mismatch");
    }
    private void verifyCancelClaim(String claim) {
        if (cancelClaim == null || !cancelClaim.equals(claim)) throw new IllegalStateException("Cancellation claim mismatch");
    }
    public RecoveryKind recoveryKind() {
        if (status == Status.APPROVING || status == Status.UNKNOWN) return RecoveryKind.APPROVAL_LOOKUP;
        if (status == Status.CANCEL_PENDING) return cancelClaim == null ? RecoveryKind.CANCEL_DISPATCH : RecoveryKind.CANCEL_LOOKUP;
        return null;
    }
    public boolean claimRecovery(Instant now, java.time.Duration lease, java.time.Duration grace, int maximum) {
        if (recoveryKind() == null || recoveryAttempts >= maximum
                || recoveryLeaseUntil != null && recoveryLeaseUntil.isAfter(now)
                || recoveryNextAt != null && recoveryNextAt.isAfter(now)) return false;
        // Do not query while a recent foreground approval/void is normally still in flight.
        Instant started = status == Status.APPROVING ? approvalStartedAt
                : status == Status.CANCEL_PENDING && cancelClaim != null ? cancelStartedAt : null;
        if (started != null && started.plus(grace).isAfter(now)) return false;
        if (recoveryToken != null) { recoveryFailure = "LEASE_EXPIRED"; recoveryFailedAt = now; }
        recoveryToken = UUID.randomUUID().toString(); recoveryLeaseUntil = now.plus(lease); recoveryAttempts++;
        return true;
    }
    public void requireRecovery(String token, Instant now) {
        if (token == null || !token.equals(recoveryToken) || recoveryLeaseUntil == null || !recoveryLeaseUntil.isAfter(now))
            throw new IllegalStateException("Recovery lease is stale");
    }
    public boolean finishRecovery(String token, RecoveryKind originalKind, Instant now, java.time.Duration delay, String failure) {
        if (token == null || !token.equals(recoveryToken) || recoveryLeaseUntil == null || !recoveryLeaseUntil.isAfter(now)) return false;
        recoveryToken = null; recoveryLeaseUntil = null;
        if (recoveryKind() == null) { recoveryNextAt = null; recoveryFailure = null; return true; }
        if (originalKind == RecoveryKind.APPROVAL_LOOKUP && recoveryKind() == RecoveryKind.CANCEL_DISPATCH) {
            recoveryAttempts = 0; recoveryNextAt = now; recoveryFailure = null; return true;
        }
        recoveryNextAt = now.plus(delay); recoveryFailure = failure; recoveryFailedAt = now; return true;
    }
}
