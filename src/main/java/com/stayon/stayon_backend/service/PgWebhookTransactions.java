package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PgWebhookProperties;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.PgWebhookInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.function.Supplier;

@Service
public class PgWebhookTransactions {
    private final PgWebhookInboxRepository inbox;
    private final PaymentTransactions payments;
    private final PgWebhookProperties settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public PgWebhookTransactions(PgWebhookInboxRepository inbox, PaymentTransactions payments, PgWebhookProperties settings,
            Clock clock, PlatformTransactionManager manager) {
        this.inbox = inbox; this.payments = payments; this.settings = settings; this.clock = clock;
        transaction = new TransactionTemplate(manager); transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(10);
    }
    public record Accepted(boolean duplicate) { }
    public record Lease(String id, String token, String provider, String attemptId, int attempt,String merchantId) { }
    public Accepted store(String provider,String merchantId, String eventHash, String attemptId, String bodyHash,String transactionId) {
        try {
            return transaction.execute(status -> {
                var existing = inbox.findByProviderAndMerchantIdAndEventKeyHash(provider,merchantId, eventHash);
                if (existing.isPresent()) { sameReference(existing.get(), attemptId,transactionId); return new Accepted(true); }
                inbox.saveAndFlush(new PgWebhookInbox(provider,merchantId,eventHash,attemptId,bodyHash,transactionId,clock.instant())); return new Accepted(false);
            });
        } catch (DataIntegrityViolationException e) {
            // Unique-index loser must recover in a NEW transaction after the failed INSERT rolled back.
            return tx(() -> {
                var existing = inbox.findByProviderAndMerchantIdAndEventKeyHash(provider,merchantId, eventHash).orElseThrow(PgWebhookTransactions::unavailable);
                sameReference(existing, attemptId,transactionId); return new Accepted(true);
            });
        } catch (DataAccessException | TransactionException e) { throw unavailable(); }
    }
    public Lease claim(String id) {
        settings.validate();
        return tx(() -> {
            var row = inbox.lockById(id).orElseThrow(PgWebhookTransactions::missing);
            boolean claimed = row.claim(clock.instant(), settings.getLease(), settings.getMaxAttempts()); inbox.flush();
            return claimed ? new Lease(row.getId(), row.getLeaseToken(), row.getProvider(), row.getAttemptId(), row.getAttempts(),row.getMerchantId()) : null;
        });
    }
    public void apply(Lease lease, PaymentOrchestrator.WebhookObservation observation) {
        if (!lease.attemptId().equals(observation.id()) || !lease.provider().equals(observation.provider())||!lease.merchantId().equals(observation.merchantId())) throw conflict();
        Runnable gate = () -> {
            var row = inbox.lockById(lease.id()).orElseThrow(PgWebhookTransactions::missing);
            row.requireLease(lease.token(), clock.instant());
            if (!row.getAttemptId().equals(observation.id()) || !row.getProvider().equals(observation.provider())) throw conflict();
            if(!row.getMerchantId().equals(observation.merchantId()))throw conflict();
            var pgTransaction=observation.approval()!=null?observation.approval().transactionId():observation.cancellation().transactionId();
            if(!observation.unknown()&&row.getPgTransactionId()!=null&&!row.getPgTransactionId().equals(pgTransaction))throw conflict();
            if (observation.unknown()) row.retry(lease.token(), clock.instant(), settings.backoff(row.getAttempts()), settings.getMaxAttempts(), "RESULT_UNKNOWN");
            else row.complete(lease.token(), clock.instant());
            inbox.flush();
        };
        // No outer Inbox transaction or lock. PaymentTransactions opens the one transaction and invokes the gate last in lock order.
        if (observation.approval() != null && observation.cancellation() == null)
            payments.applyWebhookApproval(observation.id(),observation.merchantId(), observation.claim(), observation.approval(), gate);
        else if (observation.cancellation() != null && observation.approval() == null)
            payments.applyWebhookCancellation(observation.id(),observation.merchantId(), observation.claim(), observation.cancellation(), gate);
        else throw conflict();
    }
    public boolean retry(Lease lease, String failure) {
        return tx(() -> {
            var row = inbox.lockById(lease.id()).orElseThrow(PgWebhookTransactions::missing);
            boolean changed = row.retry(lease.token(), clock.instant(), settings.backoff(row.getAttempts()), settings.getMaxAttempts(), failure);
            inbox.flush(); return changed;
        });
    }
    private void sameReference(PgWebhookInbox row, String attemptId,String transactionId) { if (!row.getAttemptId().equals(attemptId)||!java.util.Objects.equals(row.getPgTransactionId(),transactionId)) throw conflict(); }
    private <T> T tx(Supplier<T> work) {
        try { return transaction.execute(status -> { try { return work.get(); } catch (IllegalStateException e) { throw conflict(); } }); }
        catch (DataAccessException | TransactionException e) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook persistence is unavailable"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Webhook state conflict"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
