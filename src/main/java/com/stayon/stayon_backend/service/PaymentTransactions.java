package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/** Short DB stages only. All result application uses reservation -> order -> attempt locks. */
@Service
public class PaymentTransactions {
    private final PaymentAttemptRepository attempts;
    private final ServiceOrderRepository orders;
    private final ReservationRepository reservations;
    private final ReservationAccessPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final PaymentProviderPolicy providers;
    private final PgMerchantRouting routing;
    public PaymentTransactions(PaymentAttemptRepository attempts, ServiceOrderRepository orders,
            ReservationRepository reservations, ReservationAccessPolicy policy, Clock clock, PlatformTransactionManager manager, PaymentProviderPolicy providers, PgMerchantRouting routing) {
        this.attempts = attempts; this.orders = orders; this.reservations = reservations; this.policy = policy; this.clock = clock;
        this.providers = providers;
        this.routing = routing;
        transaction = new TransactionTemplate(manager); transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(10);
    }
    public record View(String id, Long orderId, String provider, long amount, String currency, PaymentAttempt.Status status,
                       String approvalClaim, String cancelClaim, String transactionId, boolean cancelRequested, boolean cancelUnknown, PgMerchantContext merchant,Preparation preparation) {
        public PgAdapter.Command command(boolean cancel) {
            return new PgAdapter.Command(id, provider, orderId, amount, currency, id + (cancel ? ":cancel" : ":approve"), transactionId, merchant,
                    preparation==null?null:new PgAdapter.Preparation(preparation.tid(),preparation.userReference(),preparation.taxFreeAmount()));
        }
    }
    public record Claim(View view, boolean claimed) { }
    public record Preparation(PaymentAttempt.PreparationStatus status,String claim,String tid,String userReference,long taxFreeAmount,
                              String sessionHash,java.time.Instant expiresAt,String pcUrl,String mobileUrl){}
    public record Creation(View view, boolean created) { }
    public record RecoveryLease(String id, String token, PaymentAttempt.RecoveryKind kind, int attempt) { }
    public record Cancellation(boolean changed, boolean accepted) { }
    private record Locked(ServiceOrder order, PaymentAttempt attempt) { }

    public View createAttempt(Authentication authentication, Long orderId, String provider, String key) {
        return createAttemptResult(authentication, orderId, provider, key).view();
    }
    public Creation createAttemptResult(Authentication authentication, Long orderId, String provider, String key) {
        return tx(() -> {
            // Authorization precedes key replay and business conflict disclosure.
            var reservation = policy.lockOrderCreationScope(authentication);
            if (orderId == null || orderId <= 0) throw missing();
            var order = orders.findForCustomerCancellationForUpdate(orderId, reservation.getId()).orElseThrow(PaymentTransactions::missing);
            if (!PaymentAttempt.validProvider(provider) || key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            String hash = PaymentAttempt.digest(key);
            var existing = attempts.findByServiceOrder_IdAndIdempotencyKeyHash(orderId, hash);
            if (existing.isPresent()) {
                if (!existing.get().getProvider().equals(provider)) throw conflict();
                return new Creation(view(existing.get()), false);
            }
            if (order.getStatus() != ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT || attempts.lockActive(orderId).isPresent()) throw conflict();
            policy.authorizeReservation(authentication, reservation.getId(), ReservationAccessPolicy.Operation.CREATE_ORDER);
            providers.requireNewPayment(provider, order);
            var merchant=routing.select(order,provider);var attempt=new PaymentAttempt(order,provider,hash,clock.instant());attempt.pinMerchant(merchant);
            return new Creation(view(attempts.saveAndFlush(attempt)), true);
        });
    }
    public View inspect(Authentication authentication, String id) { return tx(() -> view(lockCustomer(authentication, id).attempt())); }
    public View inspectInternal(String id) { return tx(() -> view(lockInternal(id).attempt())); }
    public void validateWebhookReference(String id,String provider,String merchantId,String transactionId){
        tx(()->{
            if(attempts.findRoute(id).isEmpty())return null;
            var a=lockInternal(id).attempt();
            if(!a.getProvider().equals(provider)||a.getMerchant()==null||!a.getMerchant().getId().equals(merchantId)
                    ||transactionId!=null&&a.getPgTransactionId()!=null&&!transactionId.equals(a.getPgTransactionId()))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Webhook merchant or transaction mismatch");
            return null;
        });
    }
    public Claim claimApproval(Authentication authentication, String id) {
        return claimApproval(authentication,id,PgAdapter.BrowserAuthentication.none());
    }
    public Claim claimApproval(Authentication authentication,String id,PgAdapter.BrowserAuthentication proof){
        return tx(() -> {
            var locked = lockCustomer(authentication, id, true); var a = locked.attempt();
            if (a.getStatus() != PaymentAttempt.Status.READY) return new Claim(view(a), false);
            if (locked.order().getStatus() != ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT) throw conflict();
            policy.authorizeReservation(authentication, locked.order().getReservation().getId(), ReservationAccessPolicy.Operation.CREATE_ORDER);
            providers.requireNewPayment(a.getProvider(), locked.order());
            if(a.getProvider().equals("KAKAOPAY"))a.consumeCallback(proof.payload().get("state"),PaymentAttempt.digest(((com.stayon.stayon_backend.security.CustomerPrincipal)authentication.getPrincipal()).sessionId()),clock.instant());
            a.claimApproval(clock.instant()); locked.order().beginPayment(); attempts.flush(); return new Claim(view(a), true);
        });
    }
    public View applyApproval(String id, String claim, PgAdapter.ApprovalResult result) {
        return applyApproval(id, claim, result, null);
    }
    public Claim claimPreparation(Authentication auth,String id,String stateHash,java.time.Instant expiresAt,long taxFreeAmount){
        return tx(()->{
            var locked=lockCustomer(auth,id,true);var a=locked.attempt();
            if(a.getStatus()!=PaymentAttempt.Status.READY||locked.order().getStatus()!=ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT)throw conflict();
            policy.authorizeReservation(auth,locked.order().getReservation().getId(),ReservationAccessPolicy.Operation.CREATE_ORDER);
            providers.requireNewPayment(a.getProvider(),locked.order());
            boolean claimed=a.claimPreparation(stateHash,PaymentAttempt.digest(((com.stayon.stayon_backend.security.CustomerPrincipal)auth.getPrincipal()).sessionId()),expiresAt,taxFreeAmount,clock.instant());
            attempts.flush();return new Claim(view(a),claimed);
        });
    }
    public View applyPreparation(String id,String claim,String tid,String pcUrl,String mobileUrl){
        return tx(()->{var a=lockInternal(id).attempt();a.prepared(claim,tid,pcUrl,mobileUrl,clock.instant());attempts.flush();return view(a);});
    }
    public View preparationUnknown(String id,String claim){
        return tx(()->{var a=lockInternal(id).attempt();a.preparationUnknown(claim,clock.instant());attempts.flush();return view(a);});
    }
    public View applyApproval(String id, String claim, PgAdapter.ApprovalResult result, String recoveryToken) {
        return tx(() -> applyApprovalLocked(lockInternal(id), claim, result, recoveryToken));
    }
    /** The gate locks/validates Inbox only AFTER reservation -> order -> attempt. Its changes commit atomically with payment. */
    View applyWebhookApproval(String id, String merchantId,String claim, PgAdapter.ApprovalResult result, Runnable inboxGate) {
        return tx(() -> { var locked = lockInternal(id);if(!java.util.Objects.equals(merchantId,locked.attempt().getMerchantId()))throw conflict(); inboxGate.run(); return applyApprovalLocked(locked, claim, result, null); });
    }
    private View applyApprovalLocked(Locked locked, String claim, PgAdapter.ApprovalResult result, String recoveryToken) {
            if (result == null) throw conflict();
            var a = locked.attempt(); validate(a, result.reference(), result.provider(), result.amount(), result.currency());
            if (recoveryToken != null) a.requireRecovery(recoveryToken, clock.instant());
            if (result.outcome() == null) throw conflict();
            if(result.outcome()!=PgAdapter.ApprovalOutcome.UNKNOWN)validateMerchant(a,result.pgMerchantId());
            if(a.getProvider().equals("KAKAOPAY")&&result.outcome()!=PgAdapter.ApprovalOutcome.UNKNOWN&&!java.util.Objects.equals(a.getPreparedTid(),result.transactionId()))throw conflict();
            switch (result.outcome()) {
                case UNKNOWN -> a.unknown(claim, clock.instant());
                case APPROVED -> {
                    if (a.approve(claim, result.transactionId(), clock.instant())) locked.order().paymentApproved(a.getCancelRequestedAt() != null);
                }
                case DECLINED -> {
                    if (a.decline(claim, clock.instant())) locked.order().paymentDeclined(a.getCancelRequestedAt() != null);
                }
            }
            attempts.flush(); return view(a);
    }
    /** Participates in CustomerOrderService's existing write transaction; never starts a nested independent stage. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Cancellation requestCancellationLocked(ServiceOrder order) {
        var active = attempts.lockActive(order.getId());
        if (order.canRequestPaymentCancellation()) {
            var a = active.orElseThrow(PaymentTransactions::conflict);
            boolean changed = a.requestCancel(clock.instant());
            changed |= order.requestPaymentCancellation();
            return new Cancellation(changed, true);
        }
        if (order.getStatus() == ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT && active.isPresent()) {
            if (active.get().getStatus() != PaymentAttempt.Status.READY) throw conflict();
            active.get().requestCancel(clock.instant());
        }
        return new Cancellation(order.cancelUnpaid(), false);
    }
    public Claim claimCancellation(String id) {
        return claimCancellation(id, null);
    }
    public Claim claimCancellation(String id, String recoveryToken) {
        return tx(() -> {
            var locked = lockInternal(id); var a = locked.attempt();
            if (recoveryToken != null) a.requireRecovery(recoveryToken, clock.instant());
            if (a.getStatus() != PaymentAttempt.Status.CANCEL_PENDING) return new Claim(view(a), false);
            if (locked.order().getStatus() != ServiceOrder.ServiceOrderStatus.CANCEL_PENDING) throw conflict();
            boolean claimed = a.claimCancel(clock.instant()); attempts.flush(); return new Claim(view(a), claimed);
        });
    }
    public View applyCancellation(String id, String claim, PgAdapter.CancelResult result) {
        return applyCancellation(id, claim, result, null);
    }
    public View applyCancellation(String id, String claim, PgAdapter.CancelResult result, String recoveryToken) {
        return tx(() -> applyCancellationLocked(lockInternal(id), claim, result, recoveryToken));
    }
    View applyWebhookCancellation(String id,String merchantId, String claim, PgAdapter.CancelResult result, Runnable inboxGate) {
        return tx(() -> { var locked = lockInternal(id);if(!java.util.Objects.equals(merchantId,locked.attempt().getMerchantId()))throw conflict(); inboxGate.run(); return applyCancellationLocked(locked, claim, result, null); });
    }
    private View applyCancellationLocked(Locked locked, String claim, PgAdapter.CancelResult result, String recoveryToken) {
            if (result == null) throw conflict();
            var a = locked.attempt(); validate(a, result.reference(), result.provider(), result.amount(), result.currency());
            if (recoveryToken != null) a.requireRecovery(recoveryToken, clock.instant());
            if (!Objects.equals(a.getPgTransactionId(), result.transactionId()) || result.outcome() == null) throw conflict();
            if(result.outcome()!=PgAdapter.CancelOutcome.UNKNOWN)validateMerchant(a,result.pgMerchantId());
            if (result.outcome() == PgAdapter.CancelOutcome.CANCELED) {
                if (a.canceled(claim, clock.instant())) locked.order().completePaymentCancellation();
            } else a.cancellationUnknown(claim, clock.instant());
            attempts.flush(); return view(a);
    }
    public RecoveryLease claimRecovery(String id, com.stayon.stayon_backend.config.PaymentRecoveryProperties settings) {
        settings.validate();
        return tx(() -> {
            var a = lockInternal(id).attempt();
            if (!a.claimRecovery(clock.instant(), settings.getLease(), settings.getInitialGrace(), settings.getMaxAttempts())) return null;
            attempts.flush(); return new RecoveryLease(id, a.getRecoveryToken(), a.recoveryKind(), a.getRecoveryAttempts());
        });
    }
    public boolean finishRecovery(RecoveryLease lease, java.time.Duration delay, String failure) {
        return tx(() -> {
            var a = lockInternal(lease.id()).attempt();
            boolean changed = a.finishRecovery(lease.token(), lease.kind(), clock.instant(), delay, failure);
            attempts.flush(); return changed;
        });
    }
    private Locked lockCustomer(Authentication auth, String id) {
        return lockCustomer(auth,id,false);
    }
    private Locked lockCustomer(Authentication auth, String id, boolean approval) {
        var reservation = policy.lockOrderCreationScope(auth);
        var route = attempts.findRoute(id).filter(r -> r.getReservationId().equals(reservation.getId())).orElseThrow(PaymentTransactions::missing);
        var order = orders.findForCustomerCancellationForUpdate(route.getOrderId(), reservation.getId()).orElseThrow(PaymentTransactions::missing);
        if(approval){var initial=attempts.findById(id).orElseThrow(PaymentTransactions::missing);if(initial.getStatus()==PaymentAttempt.Status.READY)routing.requireApproval(order,initial);}
        return locked(order, attempts.lockById(id, order.getId()).orElseThrow(PaymentTransactions::missing));
    }
    private Locked lockInternal(String id) {
        var route = attempts.findRoute(id).orElseThrow(PaymentTransactions::missing);
        reservations.findForCustomerOrderForUpdate(route.getReservationId()).orElseThrow(PaymentTransactions::missing);
        var order = orders.findForCustomerCancellationForUpdate(route.getOrderId(), route.getReservationId()).orElseThrow(PaymentTransactions::missing);
        return locked(order, attempts.lockById(id, order.getId()).orElseThrow(PaymentTransactions::missing));
    }
    private Locked locked(ServiceOrder order, PaymentAttempt attempt) {
        if (order.getTotalAmount() != attempt.getAmount()) throw conflict();
        if(attempt.getMerchant()!=null&&!attempt.getProvider().equals(attempt.getMerchant().getProvider()))throw conflict();
        return new Locked(order, attempt);
    }
    private View view(PaymentAttempt a) {
        return new View(a.getId(), a.getServiceOrder().getId(), a.getProvider(), a.getAmount(), a.getCurrency(), a.getStatus(),
                a.getApprovalClaim(), a.getCancelClaim(), a.getPgTransactionId(), a.getCancelRequestedAt() != null, a.isCancelUnknown(), a.getMerchant()==null?null:PgMerchantContext.from(a.getMerchant()),
                new Preparation(a.getPreparationStatus(),a.getPreparationClaim(),a.getPreparedTid(),a.getPgUserReference(),a.getTaxFreeAmount(),a.getCallbackSessionHash(),a.getCallbackExpiresAt(),a.getPaymentPcUrl(),a.getPaymentMobileUrl()));
    }
    private void validate(PaymentAttempt a, String id, String provider, long amount, String currency) {
        if (!a.getId().equals(id) || !a.getProvider().equals(provider) || a.getAmount() != amount || !a.getCurrency().equals(currency)) throw conflict();
    }
    private void validateMerchant(PaymentAttempt a,String externalId){
        if(a.getMerchant()==null||externalId==null||!a.getMerchant().getExternalId().equals(externalId))throw conflict();
    }
    private <T> T tx(Supplier<T> work) {
        try { return transaction.execute(status -> {
            try { return work.get(); }
            catch (IllegalStateException | IllegalArgumentException e) { throw conflict(); }
        }); }
        catch (DataAccessException | TransactionException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment state could not be committed; reconcile the existing reference"); }
    }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Payment state conflict"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
