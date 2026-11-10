package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "service_order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_order_id", nullable = false)
    private ServiceOrder serviceOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "additional_service_id", nullable = false)
    private AdditionalService additionalService;

    // Nullable for historical orders whose original product name is unknown.
    @Column(name = "service_name", length = 255, updatable = false)
    private String serviceName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private int unitPrice;

    @Column(nullable = false, updatable = false)
    private int totalPrice;

    ServiceOrderItem(ServiceOrder order, AdditionalService service, String serviceName,
                     int unitPrice, int quantity, int totalPrice) {
        if (order == null || service == null || service.getId() == null
                || serviceName == null || serviceName.isBlank() || serviceName.length() > 255
                || unitPrice < 0 || quantity <= 0 || totalPrice != Math.multiplyExact(unitPrice, quantity)) {
            throw new IllegalArgumentException("유효한 주문 상품 스냅샷이 필요합니다.");
        }
        this.serviceOrder = order;
        this.additionalService = service;
        this.serviceName = serviceName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.fulfillmentStatus = FulfillmentStatus.NOT_APPLICABLE;
    }

    // 시간 예약형 서비스에서 사용
    private LocalDate serviceDate;
    private LocalTime deliveryTime;

    public enum FulfillmentStatus {
        NOT_APPLICABLE,
        SCHEDULED,
        PREPARING,
        DELIVERED,
        CANCELLED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfillmentStatus fulfillmentStatus;

    private LocalDateTime deliveredAt;
}
