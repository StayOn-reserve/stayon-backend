package com.stayon.stayon_backend.dto.accommodation;

import com.stayon.stayon_backend.entity.Platform;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccommodationCreateRequestDto {

    private String accommodationName;

    private Platform platform;
}