package com.stayon.stayon_backend.dto.accommodation;

import com.stayon.stayon_backend.entity.Platform;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccommodationResponseDto {

    private Long accommodationId;
    private String accommodationName;
    private Platform platform;
    private Boolean isActive;
    @Builder
    public AccommodationResponseDto(
            Long accommodationId,
            String accommodationName,
            Platform platform,
            Boolean isActive
    ) {
        this.accommodationId = accommodationId;
        this.accommodationName = accommodationName;
        this.platform = platform;
        this.isActive = isActive;
    }
}