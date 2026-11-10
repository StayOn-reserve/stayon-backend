package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PgWebhookProperties;
import com.stayon.stayon_backend.entity.PgWebhookInbox;
import com.stayon.stayon_backend.repository.PgWebhookInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgWebhookWorker {
    private final PgWebhookInboxRepository inbox;
    private final PgWebhookTransactions transactions;
    private final PaymentOrchestrator orchestrator;
    private final PgWebhookProperties settings;
    private final Clock clock;
    public int drain(int limit) {
        settings.validate();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch size");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Webhook worker requires no outer transaction");
        int claimed = 0;
        for (var id : inbox.findDue(Set.of(PgWebhookInbox.Status.RECEIVED, PgWebhookInbox.Status.PROCESSING, PgWebhookInbox.Status.RETRY), clock.instant(), PageRequest.of(0, limit))) {
            PgWebhookTransactions.Lease lease;
            try { lease = transactions.claim(id); }
            catch (ResponseStatusException e) { log.warn("Webhook claim deferred (HTTP {})", e.getStatusCode().value()); continue; }
            if (lease == null) continue;
            claimed++;
            try { transactions.apply(lease, orchestrator.observeWebhook(lease.attemptId(), lease.provider(),lease.merchantId())); }
            catch (RuntimeException e) {
                String failure = e instanceof ResponseStatusException r && r.getStatusCode().value() == 409 ? "STATE_CONFLICT" : "PROCESSING_UNAVAILABLE";
                try { transactions.retry(lease, failure); }
                catch (ResponseStatusException unavailable) { log.warn("Webhook retry persistence deferred (HTTP {})", unavailable.getStatusCode().value()); }
            }
        }
        return claimed;
    }
}
