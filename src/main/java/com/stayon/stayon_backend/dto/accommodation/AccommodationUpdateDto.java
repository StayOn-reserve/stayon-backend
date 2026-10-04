package com.stayon.stayon_backend.dto.accommodation;

import com.stayon.stayon_backend.entity.ReservationProvider;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AccommodationUpdateDto {
    private Long accommodationId;
    private String accommodationName;
    private ReservationProvider reservationProvider;
    private Boolean isActive;
    @Builder
    public AccommodationUpdateDto(
            Long accommodationId,
            String accommodationName,
            ReservationProvider reservationProvider,
            Boolean isActive
    ) {
        this.accommodationId = accommodationId;
        this.accommodationName = accommodationName;
        this.reservationProvider = reservationProvider;
        this.isActive = isActive;
    }
}
