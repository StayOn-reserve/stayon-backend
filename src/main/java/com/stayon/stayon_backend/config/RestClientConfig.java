package com.stayon.stayon_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    @Value("${business-api.url}")
    private String businessApibaseUrl;
    @Value("${trip-eleven.base-url}")
    private String tripElevenBaseUrl;
    @Bean
    public RestClient businessRestClient() {

        return RestClient.builder()
                .baseUrl(businessApibaseUrl)
                .build();
    }
    @Bean
    public RestClient tripElevenRestClient() {

        return RestClient.builder()
                .baseUrl(tripElevenBaseUrl)
                .build();
    }
}