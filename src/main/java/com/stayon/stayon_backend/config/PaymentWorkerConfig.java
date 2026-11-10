package com.stayon.stayon_backend.config;

import com.stayon.stayon_backend.service.PaymentCancellationWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.cancellation-worker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PaymentWorkerConfig {
    private final PaymentCancellationWorker worker;
    @Scheduled(fixedDelayString = "${payment.cancellation-worker.delay-ms:5000}")
    public void drain() { worker.drain(20); }
}
