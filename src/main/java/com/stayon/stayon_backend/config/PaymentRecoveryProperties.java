package com.stayon.stayon_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "payment.recovery")
public class PaymentRecoveryProperties {
    private boolean enabled = false;
    private int batchSize = 20;
    private int maxAttempts = 8;
    private Duration lease = Duration.ofSeconds(60);
    private Duration initialGrace = Duration.ofSeconds(60);
    private Duration baseBackoff = Duration.ofSeconds(5);
    private Duration maxBackoff = Duration.ofMinutes(15);
    public void validate() {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 100
                || lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofHours(1)) > 0
                || initialGrace == null || initialGrace.isNegative() || initialGrace.compareTo(Duration.ofHours(1)) > 0
                || baseBackoff == null || baseBackoff.compareTo(Duration.ofMillis(1)) < 0
                || maxBackoff == null || maxBackoff.compareTo(baseBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Invalid payment recovery settings");
    }
    public Duration backoff(int attempt) {
        validate(); long millis = baseBackoff.toMillis(), cap = maxBackoff.toMillis();
        for (int i = 1; i < attempt && millis < cap; i++) millis = millis > cap / 2 ? cap : millis * 2;
        return Duration.ofMillis(Math.min(millis, cap));
    }
}
