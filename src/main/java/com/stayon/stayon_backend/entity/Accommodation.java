package com.stayon.stayon_backend.entity;

import com.stayon.stayon_backend.dto.accommodation.AccommodationCreateRequestDto;
import com.stayon.stayon_backend.dto.accommodation.AccommodationResponseDto;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@NoArgsConstructor
public class Accommodation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long accommodationId;

    @Getter
    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @Getter
    private User owner;

    public static AccommodationResponseDto entityToDto(Accommodation accommodation) {
        return AccommodationResponseDto.builder()
                .accommodationId(accommodation.getAccommodationId())
                .accommodationName(accommodation.getName())
                .userId(accommodation.getOwner().getUserId())
                .build();
    }
    @Builder
    public Accommodation(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }
}
