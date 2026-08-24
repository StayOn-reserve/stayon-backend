package com.stayon.stayon_backend.client;

import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class BusinessApiClient {

    private final RestClient restClient;

    public BusinessVerifyResponseDto verify(
            BusinessVerifyRequestDto request
    ) {

        return restClient.post()
                .uri("/api/v1/validate")
                .body(request)
                .retrieve()
                .body(BusinessVerifyResponseDto.class);
    }
}