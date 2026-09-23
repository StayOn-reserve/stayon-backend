package com.stayon.stayon_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "trip-eleven")
public class TripElevenProperties {
    private String baseUrl;
    private String username;
    private String password;
    private String businessId;
}