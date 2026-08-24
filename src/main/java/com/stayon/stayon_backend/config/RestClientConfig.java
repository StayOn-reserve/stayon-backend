package com.stayon.stayon_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {

        return RestClient.builder()
                .baseUrl("https://외부API주소")
                .build();
    }
}