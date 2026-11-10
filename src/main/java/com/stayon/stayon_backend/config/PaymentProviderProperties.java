package com.stayon.stayon_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix = "payment.providers")
public class PaymentProviderProperties {
    private Map<String, Rule> rules = new HashMap<>();
    @Data public static class Rule {
        private boolean enabled;
        private boolean allowAllSites;
        private Set<Long> siteIds = new HashSet<>();
        private Set<String> currencies = new HashSet<>(Set.of("KRW"));
        private long minimumAmount = 1;
        private long maximumAmount = Integer.MAX_VALUE;
    }
}
