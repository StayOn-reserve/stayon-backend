package com.stayon.stayon_backend.config;

import com.stayon.stayon_backend.service.PaymentRecoveryWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PaymentRecoveryWorkerConfig {
    private final PaymentRecoveryWorker worker;
    private final PaymentRecoveryProperties settings;
    @Scheduled(fixedDelayString = "${payment.recovery.delay-ms:5000}")
    public void drain() { worker.drain(settings.getBatchSize()); }
}
