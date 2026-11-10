package com.stayon.stayon_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "customer.otp")
public class CustomerOtpProperties {
    private String hmacSecret;
    private int issueIpHourlyLimit = 50;
    private int verifyIpHourlyLimit = 100;
    private int globalHourlyLimit = 1000;
    private int globalDailyLimit = 10000;
}
