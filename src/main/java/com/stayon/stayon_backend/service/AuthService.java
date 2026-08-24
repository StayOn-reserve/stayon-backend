package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.client.BusinessApiClient;
import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final BusinessApiClient businessApiClient;

    public String authenticateBusinessNumber(BusinessVerifyRequestDto dto) {
        BusinessVerifyResponseDto response = businessApiClient.verify(dto);
        return response.isVerified() ? "Authenticated" : "Not Authenticated";
    }
}