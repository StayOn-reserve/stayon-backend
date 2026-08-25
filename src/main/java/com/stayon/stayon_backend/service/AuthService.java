package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.client.BusinessApiClient;
import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final BusinessApiClient businessApiClient;

    public String authenticateBusinessNumber(BusinessVerifyRequestDto dto) {
        BusinessVerifyResponseDto response = businessApiClient.verify(dto);
        log.info("Business verification result: {}", response.toString());
        return response.isVerified() ? "Authenticated" : "Not Authenticated";
    }
}