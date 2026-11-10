package com.stayon.stayon_backend.service;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

final class FakePgAdapter implements PgAdapter {
    enum Mode { SUCCESS, DECLINED, UNKNOWN, TIMEOUT, SUCCESS_THEN_TIMEOUT }
    volatile Mode mode = Mode.SUCCESS;
    volatile Mode cancelMode = Mode.SUCCESS;
    volatile boolean browserEnabled;
    volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
    volatile CountDownLatch cancelEntered = new CountDownLatch(0), cancelRelease = new CountDownLatch(0);
    volatile Consumer<Command> afterApproval = command -> { };
    volatile Consumer<Command> afterCancellation = command -> { };
    volatile Consumer<Command> beforeLookup = command -> { };
    final AtomicInteger approvals = new AtomicInteger(), cancels = new AtomicInteger(), lookups = new AtomicInteger();
    final ConcurrentMap<String, ApprovalResult> ledger = new ConcurrentHashMap<>();
    final ConcurrentMap<String, CancelResult> cancelLedger = new ConcurrentHashMap<>();
    final java.util.List<Command> commands=new CopyOnWriteArrayList<>();
    void reset() {
        mode = Mode.SUCCESS; cancelMode = Mode.SUCCESS; browserEnabled = false; approvals.set(0); cancels.set(0); lookups.set(0);
        entered = new CountDownLatch(0); release = new CountDownLatch(0);
        cancelEntered = new CountDownLatch(0); cancelRelease = new CountDownLatch(0);
        afterApproval = command -> { }; afterCancellation = command -> { }; beforeLookup = command -> { }; ledger.clear(); cancelLedger.clear();commands.clear();
    }
    void block() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
    void blockCancel() { cancelEntered = new CountDownLatch(1); cancelRelease = new CountDownLatch(1); }
    @Override public String provider() { return "TEST_PG"; }
    @Override public boolean testOnly() { return true; }
    private void scope(Command command,PgSecret secret){
        assertNotNull(command.merchant());assertEquals(FakePgSecretResolver.value(command.merchant().id()),new String(secret.copy()));commands.add(command);
    }
    @Override public ApprovalResult approve(Command c,BrowserAuthentication proof,PgSecret secret){scope(c,secret);validateAuthentication(proof);return attest(approve(c),c);}
    @Override public CancelResult cancel(Command c,PgSecret secret){scope(c,secret);return attest(cancel(c),c);}
    @Override public ApprovalResult lookupApproval(Command c,PgSecret secret){scope(c,secret);return attest(lookupApproval(c),c);}
    @Override public CancelResult lookupCancellation(Command c,PgSecret secret){scope(c,secret);return attest(lookupCancellation(c),c);}
    private ApprovalResult attest(ApprovalResult r,Command c){return new ApprovalResult(r.reference(),r.provider(),r.outcome(),r.transactionId(),r.amount(),r.currency(),r.pgMerchantId()==null?c.merchant().externalId():r.pgMerchantId());}
    private CancelResult attest(CancelResult r,Command c){return new CancelResult(r.reference(),r.provider(),r.outcome(),r.transactionId(),r.amount(),r.currency(),r.pgMerchantId()==null?c.merchant().externalId():r.pgMerchantId());}
    @Override public void validateAuthentication(BrowserAuthentication proof) {
        if (browserEnabled && proof.version().equals("TEST_V1") && proof.payload().size() == 1 && proof.payload().containsKey("opaqueCode")) return;
        PgAdapter.super.validateAuthentication(proof);
    }
    @Override public ApprovalResult approve(Command c) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "PG approval held a DB transaction");
        approvals.incrementAndGet(); entered.countDown(); await(release);
        if (mode == Mode.TIMEOUT) throw new IllegalStateException("simulated timeout");
        var outcome = mode == Mode.DECLINED ? ApprovalOutcome.DECLINED : mode == Mode.UNKNOWN ? ApprovalOutcome.UNKNOWN : ApprovalOutcome.APPROVED;
        var result = new ApprovalResult(c.reference(), c.provider(), outcome, outcome == ApprovalOutcome.APPROVED ? "tx-" + c.reference() : null, c.amount(), c.currency());
        ledger.put(c.reference(), result); afterApproval.accept(c);
        if (mode == Mode.SUCCESS_THEN_TIMEOUT) throw new IllegalStateException("charged but response lost");
        return result;
    }
    @Override public CancelResult cancel(Command c) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "PG cancellation held a DB transaction");
        cancels.incrementAndGet(); cancelEntered.countDown(); await(cancelRelease);
        if (cancelMode == Mode.TIMEOUT) throw new IllegalStateException("simulated cancellation timeout");
        var result = new CancelResult(c.reference(), c.provider(), cancelMode == Mode.UNKNOWN ? CancelOutcome.UNKNOWN : CancelOutcome.CANCELED,
                c.transactionId(), c.amount(), c.currency());
        cancelLedger.put(c.reference(), result); afterCancellation.accept(c);
        if (cancelMode == Mode.SUCCESS_THEN_TIMEOUT) throw new IllegalStateException("voided but response lost");
        return result;
    }
    @Override public ApprovalResult lookupApproval(Command c) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); lookups.incrementAndGet(); beforeLookup.accept(c);
        return ledger.getOrDefault(c.reference(), new ApprovalResult(c.reference(), c.provider(), ApprovalOutcome.UNKNOWN, null, c.amount(), c.currency()));
    }
    @Override public CancelResult lookupCancellation(Command c) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); lookups.incrementAndGet(); beforeLookup.accept(c);
        return cancelLedger.getOrDefault(c.reference(), new CancelResult(c.reference(), c.provider(), CancelOutcome.UNKNOWN, c.transactionId(), c.amount(), c.currency()));
    }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
