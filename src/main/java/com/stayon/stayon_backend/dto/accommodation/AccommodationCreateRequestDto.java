package com.stayon.stayon_backend.dto.accommodation;

import com.stayon.stayon_backend.entity.Accommodation;
import com.stayon.stayon_backend.entity.ReservationProvider;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccommodationCreateRequestDto {

    private String accommodationName;

    private ReservationProvider reservationProvider;
}