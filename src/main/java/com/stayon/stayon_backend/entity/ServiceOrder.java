package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "service_order", uniqueConstraints = @UniqueConstraint(name = "uk_order_reservation_idempotency",
        columnNames = {"reservation_id", "idempotency_key_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    // Nullable for legacy orders. Both are assigned together before the first persist.
    @Column(name = "idempotency_key_hash", length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    public void identifyCreation(String keyHash, String requestHash) {
        if (id != null || idempotencyKeyHash != null || this.requestHash != null
                || keyHash == null || !keyHash.matches("[0-9a-f]{64}")
                || requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("새 주문의 유효한 멱등성 정보가 필요합니다.");
        }
        this.idempotencyKeyHash = keyHash;
        this.requestHash = requestHash;
    }

    public enum ServiceOrderStatus {
        PENDING_PAYMENT,
        PAYMENT_PROCESSING,
        CANCEL_PENDING,
        PAID,
        CANCELLED,
        EXPIRED,
        REFUNDED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceOrderStatus status;

    public boolean canRequestPaymentCancellation() {
        return status == ServiceOrderStatus.PAYMENT_PROCESSING || status == ServiceOrderStatus.CANCEL_PENDING;
    }
    public void beginPayment() {
        if (status != ServiceOrderStatus.PENDING_PAYMENT) throw new IllegalStateException("Order cannot begin approval");
        status = ServiceOrderStatus.PAYMENT_PROCESSING;
    }
    public boolean requestPaymentCancellation() {
        if (status == ServiceOrderStatus.CANCEL_PENDING) return false;
        if (status != ServiceOrderStatus.PAYMENT_PROCESSING) throw new IllegalStateException("Order is not approving");
        status = ServiceOrderStatus.CANCEL_PENDING; return true;
    }
    public void paymentApproved(boolean cancellationRequested) {
        requireProcessing(); status = cancellationRequested || status == ServiceOrderStatus.CANCEL_PENDING
                ? ServiceOrderStatus.CANCEL_PENDING : ServiceOrderStatus.PAID;
    }
    public void paymentDeclined(boolean cancellationRequested) {
        requireProcessing(); status = cancellationRequested || status == ServiceOrderStatus.CANCEL_PENDING
                ? ServiceOrderStatus.CANCELLED : ServiceOrderStatus.PENDING_PAYMENT;
    }
    public void completePaymentCancellation() {
        if (status != ServiceOrderStatus.CANCEL_PENDING) throw new IllegalStateException("Order has no pending cancellation");
        status = ServiceOrderStatus.CANCELLED;
    }
    private void requireProcessing() {
        if (status != ServiceOrderStatus.PAYMENT_PROCESSING && status != ServiceOrderStatus.CANCEL_PENDING)
            throw new IllegalStateException("Order is not processing a payment");
    }

    public boolean canCancelUnpaid() {
        return status == ServiceOrderStatus.PENDING_PAYMENT || status == ServiceOrderStatus.CANCELLED;
    }

    /** Call only after locking the owned reservation and order in a write transaction. */
    public boolean cancelUnpaid() {
        if (status == ServiceOrderStatus.CANCELLED) return false;
        if (status != ServiceOrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("미결제 주문만 취소할 수 있습니다.");
        }
        status = ServiceOrderStatus.CANCELLED;
        return true;
    }

    @Column(nullable = false, updatable = false)
    private int totalAmount;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "serviceOrder", cascade = CascadeType.PERSIST)
    private List<ServiceOrderItem> items = new ArrayList<>();

    public ServiceOrder(Reservation reservation) {
        if (reservation == null || reservation.getId() == null) {
            throw new IllegalArgumentException("저장된 예약이 필요합니다.");
        }
        this.reservation = reservation;
        this.status = ServiceOrderStatus.PENDING_PAYMENT;
    }

    /** Internal snapshot assembly only; authorization belongs to the application service. */
    public void addItem(AdditionalService service, String serviceName, int unitPrice, int quantity, int totalPrice) {
        if (id != null) {
            throw new IllegalStateException("저장된 주문의 스냅샷은 변경할 수 없습니다.");
        }
        var item = new ServiceOrderItem(this, service, serviceName, unitPrice, quantity, totalPrice);
        totalAmount = Math.addExact(totalAmount, totalPrice);
        items.add(item);
    }

    public List<ServiceOrderItem> getItems() {
        return List.copyOf(items);
    }
}
