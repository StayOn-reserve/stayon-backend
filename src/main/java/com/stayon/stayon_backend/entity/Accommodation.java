package com.stayon.stayon_backend.entity;

import com.stayon.stayon_backend.dto.accommodation.AccommodationResponseDto;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@NoArgsConstructor
public class Accommodation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long accommodationId;

    @Getter
    @Setter
    @Column(nullable = false)
    private String name;

    @Getter
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_provider", nullable = false)
    private ReservationProvider reservationProvider;

    @OneToMany(mappedBy = "accommodation")
    private List<AccommodationItem> accommodationItems = new ArrayList<>();

    @Setter
    @Column(name = "is_active")
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @Getter
    private User owner;

    public AccommodationResponseDto entityToDto(
    ) {
        return AccommodationResponseDto.builder()
                .accommodationId(this.getAccommodationId())
                .accommodationName(this.getName())
                .reservationProvider(this.getReservationProvider())
                .build();
    }
    @Builder
    public Accommodation(
            String name,
            ReservationProvider reservationProvider,
            User owner
    ) {
        this.name = name;
        this.reservationProvider = reservationProvider;
        this.owner = owner;
    }
    public void changeAccommodationActive(){
        this.isActive = true;
    }
    public void changeAccommodationDeactive(){
        this.isActive = false;
    }
}
