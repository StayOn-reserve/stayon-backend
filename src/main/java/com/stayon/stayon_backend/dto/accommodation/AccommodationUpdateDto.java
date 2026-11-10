package com.stayon.stayon_backend.dto.accommodation;

import com.stayon.stayon_backend.entity.Platform;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AccommodationUpdateDto {
    private Long accommodationId;
    private String accommodationName;
    private Platform platform;
    private Boolean isActive;
    @Builder
    public AccommodationUpdateDto(
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
