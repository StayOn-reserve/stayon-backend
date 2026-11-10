package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_order_id", nullable = false)
    private ServiceOrder serviceOrder;

    @Column(nullable = false, unique = true)
    private String paymentAttemptId;

    @Column(unique = true)
    private String pgPaymentKey;

    @Column(nullable = false)
    private int amount;

    public enum PaymentStatus {
        READY,
        PAID,
        FAILED,
        CANCELLED,
        REFUNDED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;
}