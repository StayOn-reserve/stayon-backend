package com.stayon.stayon_backend.config;

import com.stayon.stayon_backend.entity.PgMerchant;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@Component
@ConfigurationProperties(prefix="payment.merchant")
public class PgMerchantProperties {
    private PgMerchant.Environment environment=PgMerchant.Environment.LIVE;
}
