package com.stayon.stayon_backend.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class AccommodationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, unique = true)
    private String itemId;

    @Column(nullable = false, name = "item_name")
    private String itemName;

    @Column(nullable = false, name = "item_name")
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accommodation_id", nullable = false)
    private Accommodation accommodation;

    @Builder
    public AccommodationItem(String itemId, Accommodation accommodation, String itemName) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.accommodation = accommodation;
    }
}
