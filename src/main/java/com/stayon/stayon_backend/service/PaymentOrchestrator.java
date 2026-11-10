package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class PaymentOrchestrator {
    private final PaymentTransactions transactions;
    private final PgAdapters adapters;
    private final PgSecrets secrets;

    public PaymentTransactions.View createAttempt(Authentication auth, Long orderId, String provider, String key) {
        return transactions.createAttempt(auth, orderId, provider, key);
    }
    public PaymentTransactions.View approve(Authentication auth, String id) {
        return approve(auth, id, PgAdapter.BrowserAuthentication.none());
    }
    public PaymentTransactions.View approve(Authentication auth, String id, PgAdapter.BrowserAuthentication authentication) {
        noTransaction();
        var initial = transactions.inspect(auth, id);
        if (initial.status() != PaymentAttempt.Status.READY) return initial;
        var adapter = adapters.require(initial.provider());
        try { adapter.validateAuthentication(authentication); }
        catch (IllegalArgumentException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid provider authentication"); }
        // Resolve before consuming the financial claim. No credential value is persisted.
        try(var secret=secrets.resolve(initial.merchant())) {
        var claim = transactions.claimApproval(auth, id,authentication);
        if (!claim.claimed()) return claim.view();
        PgAdapter.ApprovalResult result;
        try { result = adapter.approve(claim.view().command(false), authentication, secret); }
        catch (RuntimeException e) { result = unknown(claim.view()); }
        if (result == null) result = unknown(claim.view());
        validateMerchant(claim.view(),result.pgMerchantId(),result.outcome()!=PgAdapter.ApprovalOutcome.UNKNOWN);
        return transactions.applyApproval(id, claim.view().approvalClaim(), result);
        }
    }
    /** Explicit reconciliation by stable merchant reference, never blind re-approval. No periodic reconciliation is implied. */
    public PaymentTransactions.View reconcileApproval(String id) {
        return reconcileApproval(id, null);
    }
    public PaymentTransactions.View reconcileApproval(String id, String recoveryToken) {
        noTransaction(); var view = transactions.inspectInternal(id);
        if (view.status() != PaymentAttempt.Status.APPROVING && view.status() != PaymentAttempt.Status.UNKNOWN) return view;
        PgAdapter.ApprovalResult result;
        var adapter = adapters.require(view.provider());
        try(var secret=secrets.resolve(view.merchant())) {
        try { result = adapter.lookupApproval(view.command(false),secret); }
        catch (RuntimeException e) { result = unknown(view); }
        if (result == null) result = unknown(view);
        validateMerchant(view,result.pgMerchantId(),result.outcome()!=PgAdapter.ApprovalOutcome.UNKNOWN);
        return transactions.applyApproval(id, view.approvalClaim(), result, recoveryToken);
        }
    }
    public PaymentTransactions.View processCancellation(String id) {
        return processCancellation(id, null);
    }
    public PaymentTransactions.View processCancellation(String id, String recoveryToken) {
        noTransaction(); var initial = transactions.inspectInternal(id);
        if (initial.status() != PaymentAttempt.Status.CANCEL_PENDING) return initial;
        var adapter = adapters.require(initial.provider());
        if(!adapter.supportsCancellation())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Provider cancellation is not implemented");
        try(var secret=secrets.resolve(initial.merchant())) {
        var claim = transactions.claimCancellation(id, recoveryToken);
        if (!claim.claimed()) return claim.view();
        PgAdapter.CancelResult result;
        try { result = adapter.cancel(claim.view().command(true),secret); }
        catch (RuntimeException e) { result = unknownCancel(claim.view()); }
        if (result == null) result = unknownCancel(claim.view());
        validateMerchant(claim.view(),result.pgMerchantId(),result.outcome()!=PgAdapter.CancelOutcome.UNKNOWN);
        return transactions.applyCancellation(id, claim.view().cancelClaim(), result, recoveryToken);
        }
    }
    public PaymentTransactions.View reconcileCancellation(String id) {
        return reconcileCancellation(id, null);
    }
    public PaymentTransactions.View reconcileCancellation(String id, String recoveryToken) {
        noTransaction(); var view = transactions.inspectInternal(id);
        if (view.status() != PaymentAttempt.Status.CANCEL_PENDING || view.cancelClaim() == null) return view;
        PgAdapter.CancelResult result;
        var adapter = adapters.require(view.provider());
        try(var secret=secrets.resolve(view.merchant())) {
        try { result = adapter.lookupCancellation(view.command(true),secret); }
        catch (RuntimeException e) { result = unknownCancel(view); }
        if (result == null) result = unknownCancel(view);
        validateMerchant(view,result.pgMerchantId(),result.outcome()!=PgAdapter.CancelOutcome.UNKNOWN);
        return transactions.applyCancellation(id, view.cancelClaim(), result, recoveryToken);
        }
    }
    public record WebhookObservation(String id, String provider, String claim, PgAdapter.ApprovalResult approval, PgAdapter.CancelResult cancellation, String merchantId) {
        public boolean unknown() {
            return approval != null ? approval.outcome() == PgAdapter.ApprovalOutcome.UNKNOWN
                    : cancellation == null || cancellation.outcome() == PgAdapter.CancelOutcome.UNKNOWN;
        }
    }
    /** Authenticated webhook is only a reconciliation hint. Never approve/void from its asserted status. */
    public WebhookObservation observeWebhook(String id, String provider) {
        return observeWebhook(id,provider,null);
    }
    public WebhookObservation observeWebhook(String id, String provider,String expectedMerchant) {
        noTransaction(); var view = transactions.inspectInternal(id);
        if (!view.provider().equals(provider)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
        if(expectedMerchant!=null&&(view.merchant()==null||!expectedMerchant.equals(view.merchant().id())))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
        var adapter = adapters.require(provider);
        try(var secret=secrets.resolve(view.merchant())) {
        if (view.cancelClaim() != null) {
            PgAdapter.CancelResult result;
            try { result = adapter.lookupCancellation(view.command(true),secret); } catch (RuntimeException e) { result = unknownCancel(view); }
            if(result==null)result=unknownCancel(view);validateMerchant(view,result.pgMerchantId(),result.outcome()!=PgAdapter.CancelOutcome.UNKNOWN);
            return new WebhookObservation(id, provider, view.cancelClaim(), null, result, view.merchant().id());
        }
        if (view.approvalClaim() == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Payment was not claimed");
        PgAdapter.ApprovalResult result;
        try { result = adapter.lookupApproval(view.command(false),secret); } catch (RuntimeException e) { result = unknown(view); }
        if(result==null)result=unknown(view);validateMerchant(view,result.pgMerchantId(),result.outcome()!=PgAdapter.ApprovalOutcome.UNKNOWN);
        return new WebhookObservation(id, provider, view.approvalClaim(), result, null, view.merchant().id());
        }
    }
    private void validateMerchant(PaymentTransactions.View view,String identity,boolean definitive){
        // UNKNOWN carries no asserted external identity; all definitive results must attest the pinned merchant.
        if(!definitive)return;
        if(identity==null||view.merchant()==null||!identity.equals(view.merchant().externalId()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Merchant result mismatch");
    }
    private PgAdapter.ApprovalResult unknown(PaymentTransactions.View v) {
        return new PgAdapter.ApprovalResult(v.id(), v.provider(), PgAdapter.ApprovalOutcome.UNKNOWN, null, v.amount(), v.currency());
    }
    private PgAdapter.CancelResult unknownCancel(PaymentTransactions.View v) {
        return new PgAdapter.CancelResult(v.id(), v.provider(), PgAdapter.CancelOutcome.UNKNOWN, v.transactionId(), v.amount(), v.currency());
    }
    private void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG orchestration must run outside every DB transaction");
    }
}
