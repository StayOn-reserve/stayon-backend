package com.stayon.stayon_backend.dto.additionalservice;

import com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType;
import java.time.LocalTime;

public record AdditionalServiceRequestDto(String name, Integer price, ServiceInputType inputType,
                                          Boolean active, LocalTime openTime, LocalTime closeTime,
                                          Integer intervalMinutes) {
}
