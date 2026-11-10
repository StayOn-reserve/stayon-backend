package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PaymentRecoveryProperties;
import com.stayon.stayon_backend.config.PaymentRecoveryWorkerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryConfigurationTests {
    @Test void schedulerIsAbsentByDefault() {
        var worker = mock(PaymentRecoveryWorker.class);
        runner(worker).run(context -> {
            assertFalse(context.containsBean("paymentRecoveryWorkerConfig")); verifyNoInteractions(worker);
        });
    }
    @Test void explicitlyEnabledTestSchedulerInvokesWorker() {
        var worker = mock(PaymentRecoveryWorker.class);
        runner(worker).withPropertyValues("payment.recovery.enabled=true", "payment.recovery.delay-ms=10").run(context -> {
            assertNull(context.getStartupFailure()); verify(worker, timeout(2000).atLeastOnce()).drain(20);
        });
    }
    private ApplicationContextRunner runner(PaymentRecoveryWorker worker) {
        return new ApplicationContextRunner().withUserConfiguration(PaymentRecoveryWorkerConfig.class)
                .withBean(PaymentRecoveryWorker.class, () -> worker)
                .withBean(PaymentRecoveryProperties.class, PaymentRecoveryProperties::new);
    }
    @Test void backoffIsCappedAndInvalidSettingsCannotStartABatch() {
        var settings = new PaymentRecoveryProperties(); settings.validate();
        assertEquals(Duration.ofSeconds(5), settings.backoff(1)); assertEquals(Duration.ofSeconds(10), settings.backoff(2));
        assertEquals(Duration.ofMinutes(15), settings.backoff(100));
        settings.setMaxAttempts(0); assertThrows(IllegalArgumentException.class, settings::validate);
        settings.setMaxAttempts(8); settings.setLease(Duration.ZERO); assertThrows(IllegalArgumentException.class, settings::validate);
        settings.setLease(Duration.ofSeconds(60)); settings.setBaseBackoff(Duration.ofNanos(1)); assertThrows(IllegalArgumentException.class, settings::validate);
    }
}
