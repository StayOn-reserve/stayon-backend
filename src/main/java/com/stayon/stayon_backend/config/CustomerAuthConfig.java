package com.stayon.stayon_backend.config;

import com.stayon.stayon_backend.service.SmsSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CustomerAuthConfig {
    @Bean
    public Clock customerAuthClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender disabledSmsSender() {
        // No real provider is configured in this phase. Never log recipient or OTP.
        return (recipient, otp, deliveryId) -> { throw new IllegalStateException("SMS provider is disabled"); };
    }
}
