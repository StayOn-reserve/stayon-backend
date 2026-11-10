package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "site")
@Getter
@NoArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "base_guest_count", nullable = false)
    private int baseGuestCount = 2;

    @Column(name = "max_guest_count", nullable = false)
    private int maxGuestCount = 6;

    @Column(name = "additional_guest_fee", nullable = false)
    private int additionalGuestFee = 30000;

    @OneToMany(
            mappedBy = "site",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<ExternalSite> externalSites = new ArrayList<>();

    // AccommodationItem 1 : N Site
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accommodation_item_id")
    private AccommodationItem accommodationItem;

    // Site 1 : N AdditionalService
    @OneToMany(mappedBy = "site")
    private List<AdditionalService> additionalServices =
            new ArrayList<>();

    public void assignAccommodationItem(
            AccommodationItem accommodationItem
    ) {
        if (accommodationItem == null) {
            throw new IllegalArgumentException(
                    "AccommodationItem은 필수입니다."
            );
        }

        this.accommodationItem = accommodationItem;
    }
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Site(String name) {
        this.name = name;
    }

    public void addExternalSite(ExternalSite externalSite) {
        externalSites.add(externalSite);
        externalSite.setSite(this);
    }

    public void removeExternalSite(ExternalSite externalSite) {
        externalSites.remove(externalSite);
        externalSite.setSite(null);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void updateGuestPolicy(
            int baseGuestCount,
            int maxGuestCount,
            int additionalGuestFee
    ) {
        if (baseGuestCount < 1 || maxGuestCount < baseGuestCount) {
            throw new IllegalArgumentException("잘못된 인원 설정입니다.");
        }

        if (additionalGuestFee < 0) {
            throw new IllegalArgumentException("추가요금은 음수일 수 없습니다.");
        }

        this.baseGuestCount = baseGuestCount;
        this.maxGuestCount = maxGuestCount;
        this.additionalGuestFee = additionalGuestFee;
    }
}
