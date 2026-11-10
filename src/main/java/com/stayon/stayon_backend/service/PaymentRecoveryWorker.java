package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PaymentRecoveryProperties;
import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.Set;

/** DB leases fence result writes; never replays an uncertain financial request. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryWorker {
    private final PaymentAttemptRepository attempts;
    private final PaymentTransactions transactions;
    private final PaymentOrchestrator orchestrator;
    private final PaymentRecoveryProperties settings;
    private final Clock clock;
    public int drain(int limit) {
        settings.validate();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch size");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Recovery requires no outer transaction");
        var now = clock.instant();
        var due = attempts.findRecoveryDue(Set.of(PaymentAttempt.Status.APPROVING, PaymentAttempt.Status.UNKNOWN, PaymentAttempt.Status.CANCEL_PENDING),
                PaymentAttempt.Status.APPROVING, PaymentAttempt.Status.CANCEL_PENDING, settings.getMaxAttempts(), now, now.minus(settings.getInitialGrace()), PageRequest.of(0, limit));
        int claimed = 0;
        for (var id : due) {
            PaymentTransactions.RecoveryLease lease;
            try { lease = transactions.claimRecovery(id, settings); }
            catch (ResponseStatusException e) { log.warn("Payment recovery claim deferred (HTTP {})", e.getStatusCode().value()); continue; }
            if (lease == null) continue;
            claimed++; String failure = "RESULT_UNKNOWN";
            try {
                switch (lease.kind()) {
                    case APPROVAL_LOOKUP -> orchestrator.reconcileApproval(id, lease.token());
                    case CANCEL_DISPATCH -> orchestrator.processCancellation(id, lease.token());
                    case CANCEL_LOOKUP -> orchestrator.reconcileCancellation(id, lease.token());
                }
            } catch (ResponseStatusException e) {
                failure = e.getStatusCode().value() == 409 ? "RESULT_CONFLICT" : "SERVICE_UNAVAILABLE";
            } catch (RuntimeException e) { failure = "INTERNAL_ERROR"; }
            if (lease.attempt() >= settings.getMaxAttempts()) failure = "EXHAUSTED_" + failure;
            try { transactions.finishRecovery(lease, settings.backoff(lease.attempt()), failure); }
            catch (ResponseStatusException e) { log.warn("Payment recovery completion deferred; durable lease will expire (HTTP {})", e.getStatusCode().value()); }
        }
        return claimed;
    }
}
