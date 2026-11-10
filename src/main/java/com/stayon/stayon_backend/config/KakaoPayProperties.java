package com.stayon.stayon_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix="payment.kakaopay")
public class KakaoPayProperties {
    public enum TaxMode { UNCONFIGURED, ALL_TAXABLE, ALL_TAX_FREE }
    private boolean enabled=false;
    private boolean mockTransport=false;
    private boolean liveEnabled=false;
    private String apiOrigin="https://open-api.kakaopay.com";
    private String approvalUrl;
    private String cancelUrl;
    private String failUrl;
    private Set<String> callbackOrigins=new HashSet<>();
    private Set<String> redirectHosts=new HashSet<>(Set.of("online-pay.kakao.com","online-payment.kakaopay.com","mockup-pg-web.kakao.com"));
    private Duration requestTimeout=Duration.ofSeconds(8);
    private Duration authenticationLifetime=Duration.ofMinutes(10);
    private TaxMode taxMode=TaxMode.UNCONFIGURED;
}
