package com.stayon.stayon_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "customer.jwt")
public class CustomerJwtProperties {
    private String secret;
    private String issuer;
    private String audience;
}
