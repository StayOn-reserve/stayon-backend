package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Durable polling entry point. Run on a worker thread, never from the customer cancellation transaction. */
@Component
@RequiredArgsConstructor
public class PaymentCancellationWorker {
    private final PaymentAttemptRepository attempts;
    private final PaymentOrchestrator orchestrator;
    public int drain(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch size");
        int done = 0;
        for (String id : attempts.findUnclaimedCancellations(PaymentAttempt.Status.CANCEL_PENDING, PageRequest.of(0, limit))) {
            try { if (orchestrator.processCancellation(id).status() == PaymentAttempt.Status.CANCELED) done++; }
            catch (ResponseStatusException e) { /* Durable row remains claimed/pending for explicit reconciliation. */ }
        }
        return done;
    }
}
