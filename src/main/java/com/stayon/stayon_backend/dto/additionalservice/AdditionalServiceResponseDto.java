package com.stayon.stayon_backend.dto.additionalservice;

import com.stayon.stayon_backend.entity.AdditionalService;
import com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType;
import java.time.LocalTime;

public record AdditionalServiceResponseDto(Long id, Long siteId, String name, int price,
                                           ServiceInputType inputType, boolean active,
                                           LocalTime openTime, LocalTime closeTime, Integer intervalMinutes) {
    public static AdditionalServiceResponseDto from(AdditionalService service) {
        return new AdditionalServiceResponseDto(service.getId(), service.getSite().getId(), service.getName(),
                service.getPrice(), service.getInputType(), service.isActive(), service.getOpenTime(),
                service.getCloseTime(), service.getIntervalMinutes());
    }
}
