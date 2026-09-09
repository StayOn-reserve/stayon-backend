package com.stayon.stayon_backend.dto.accommodation;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccommodationResponseDto {
    private Long accommodationId;
    private String accommodationName;
    private Long userId;
    @Builder
    public AccommodationResponseDto(Long accommodationId, String accommodationName, Long userId) {
        this.accommodationId = accommodationId;
        this.accommodationName = accommodationName;
        this.userId = userId;
    }
}
