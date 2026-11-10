package com.stayon.stayon_backend.config;

import com.stayon.stayon_backend.service.PgWebhookWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.webhook.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PgWebhookWorkerConfig {
    private final PgWebhookWorker worker;
    private final PgWebhookProperties settings;
    @Scheduled(fixedDelayString = "${payment.webhook.delay-ms:5000}")
    public void drain() { worker.drain(settings.getBatchSize()); }
}
