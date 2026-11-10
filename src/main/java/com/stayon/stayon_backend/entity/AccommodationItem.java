package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
public class AccommodationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, unique = true)
    private String itemId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accommodation_id", nullable = false)
    private Accommodation accommodation;

    // 신규: 하나의 AccommodationItem에는 여러 Site가 존재
    @OneToMany(mappedBy = "accommodationItem")
    private List<Site> sites = new ArrayList<>();

    @Builder
    public AccommodationItem(
            String itemId,
            Accommodation accommodation,
            String itemName
    ) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.accommodation = accommodation;
    }
}