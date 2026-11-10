package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "service_delivery_slot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_delivery_slot",
                columnNames = {
                        "accommodation_item_id",
                        "service_date",
                        "delivery_time"
                }
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceDeliverySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accommodation_item_id", nullable = false)
    private AccommodationItem accommodationItem;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "service_order_item_id",
            nullable = false,
            unique = true
    )
    private ServiceOrderItem orderItem;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "delivery_time", nullable = false)
    private LocalTime deliveryTime;

    private LocalDateTime holdExpiresAt;
}