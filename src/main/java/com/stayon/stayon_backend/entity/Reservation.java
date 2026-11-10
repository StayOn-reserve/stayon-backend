package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(
        name = "reservation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_platform_id",
                columnNames = {"platform", "reservation_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Platform platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationEventType eventType;

    @Column(name = "reservation_id", nullable = false)
    private String reservationId;

    @Column(nullable = false)
    private LocalDate checkInDate;

    @Column(nullable = false)
    private LocalDate checkOutDate;

    @Column(nullable = false)
    private int nights;

    @Column(name = "adult_count", nullable = false)
    private int adultCount = 0;

    @Column(name = "child_count", nullable = false)
    private int childCount = 0;

    @Column(name = "infant_count", nullable = false)
    private int infantCount = 0;

    private String customerName;

    private String customerPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;
    @Column(
            name = "additional_guest_fee_paid",
            nullable = false
    )
    private boolean additionalGuestFeePaid = false;

    // PENDING, SUCCESS, FAILED
    @Column(nullable = false)
    private String syncStatus = "PENDING";

    // 예약 API 필요 정보
    private String scheduleId;
    private String externalSiteId;
}
