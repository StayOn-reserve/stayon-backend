package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Entity
@Table(name = "additional_service")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdditionalService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int price;

    public enum ServiceInputType {
        QUANTITY,
        DELIVERY_TIME
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceInputType inputType;

    @Column(nullable = false)
    private boolean active = true;

    private LocalTime openTime;
    private LocalTime closeTime;
    private Integer intervalMinutes;

    public AdditionalService(Site site, String name, int price, ServiceInputType inputType,
                             boolean active, LocalTime openTime, LocalTime closeTime, Integer intervalMinutes) {
        if (site == null) {
            throw new IllegalArgumentException("Site는 필수입니다.");
        }
        this.site = site;
        update(name, price, inputType, active, openTime, closeTime, intervalMinutes);
    }

    public void update(String name, int price, ServiceInputType inputType, boolean active,
                       LocalTime openTime, LocalTime closeTime, Integer intervalMinutes) {
        if (name == null || name.isBlank() || name.length() > 255 || price < 0 || inputType == null) {
            throw new IllegalArgumentException("서비스명(1~255자), 0 이상의 가격, 입력 유형은 필수입니다.");
        }
        if ((openTime == null) != (closeTime == null)
                || (openTime != null && !openTime.isBefore(closeTime))
                || (intervalMinutes != null && intervalMinutes <= 0)) {
            throw new IllegalArgumentException("운영시간과 전달 간격이 올바르지 않습니다.");
        }
        if (inputType == ServiceInputType.DELIVERY_TIME && (openTime == null || intervalMinutes == null)) {
            throw new IllegalArgumentException("시간 예약형 서비스는 운영시간과 전달 간격이 필수입니다.");
        }
        this.name = name;
        this.price = price;
        this.inputType = inputType;
        this.active = active;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.intervalMinutes = intervalMinutes;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /** 신규 주문 트랜잭션에서 확인한 뒤 당시 이름과 가격을 주문에 복사해야 한다. */
    public void requireOrderable() {
        if (!active) {
            throw new IllegalStateException("비활성화된 서비스는 주문할 수 없습니다.");
        }
    }
}
