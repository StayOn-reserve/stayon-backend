package com.stayon.stayon_backend.client;

import com.stayon.stayon_backend.dto.business.BusinessVerifyRequestDto;
import com.stayon.stayon_backend.dto.business.BusinessVerifyResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessApiClient {

    @Value("${business-api.api-key}")
    private String serviceKey;

    private final RestClient restClient;

    public BusinessVerifyResponseDto verify(
            BusinessVerifyRequestDto request
    ) {

        return restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/validate")
                        .queryParam("serviceKey", "{serviceKey}")
                        .build(serviceKey)
                )
                .body(request)
                .retrieve()
                .body(BusinessVerifyResponseDto.class);
    }
}