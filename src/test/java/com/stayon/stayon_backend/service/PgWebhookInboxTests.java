package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.PgWebhookInboxRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(PgWebhookInboxTests.Verifiers.class)
@TestPropertySource(properties = {"payment.webhook.base-backoff=1s", "payment.webhook.max-attempts=3", "payment.recovery.initial-grace=0s"})
class PgWebhookInboxTests extends PaymentFlowTests {
    @TestConfiguration(proxyBeanMethods = false) static class Verifiers {
        @Bean @Profile("test") FakeWebhookVerifier testWebhookVerifier() { return new FakeWebhookVerifier(); }
    }
    @Autowired protected PgWebhookService receiver;
    @Autowired protected PgWebhookTransactions webhookTransactions;
    @Autowired protected PgWebhookWorker webhookWorker;
    @Autowired protected PgWebhookProperties webhookSettings;
    @Autowired protected PaymentProviderProperties providerSettings;
    @Autowired protected PaymentRecoveryWorker recovery;
    @Autowired protected Clock clock;
    @MockitoSpyBean protected PgWebhookInboxRepository inbox;
    private final Set<String> eventHashes = ConcurrentHashMap.newKeySet();
    protected String event() { String id = UUID.randomUUID().toString(); eventHashes.add(PaymentAttempt.digest(id)); return id; }
    protected byte[] body(String event, String reference, String claimed) { return (event + "|" + reference + "|" + claimed+"|"+merchantId+"|tx-"+reference).getBytes(StandardCharsets.UTF_8); }
    protected HttpHeaders headers(byte[] body) { var h = new HttpHeaders(); h.set("X-Test-Signature", FakeWebhookVerifier.signature(body)); return h; }
    protected PgWebhookTransactions.Accepted receive(String event, String reference, String claim) {
        var bytes = body(event, reference, claim); return receiver.receive("TEST_PG", bytes, headers(bytes));
    }
    protected PgWebhookInbox row(String event) { return inbox.findByProviderAndEventKeyHash("TEST_PG", PaymentAttempt.digest(event)).orElseThrow(); }
    protected void due(String event) {
        tx(() -> { ReflectionTestUtils.setField(em.find(PgWebhookInbox.class, row(event).getId()), "nextAt", clock.instant().minusSeconds(1)); return null; });
    }
    @AfterEach void cleanupInbox() {
        if (!eventHashes.isEmpty()) tx(() -> { em.createQuery("delete PgWebhookInbox i where i.provider='TEST_PG' and i.eventKeyHash in :keys").setParameter("keys", eventHashes).executeUpdate(); return null; });
        providerSettings.getRules().remove("OTHER_PG");
        var rule = providerSettings.getRules().get("TEST_PG"); rule.setEnabled(true); rule.setAllowAllSites(true);
        rule.setMinimumAmount(1); rule.setMaximumAmount(Integer.MAX_VALUE); rule.setCurrencies(new HashSet<>(Set.of("KRW")));
        rule.getSiteIds().clear();
    }
    protected PaymentTransactions.View chargedUnknown() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id()); return a;
    }
    @Test void verifiedHttpWebhookIsPersistedBeforeAcknowledgmentAndReconciledFromPg() throws Exception {
        var a = chargedUnknown(); var e = event(); var bytes = body(e, a.id(), "APPROVED");
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", FakeWebhookVerifier.signature(bytes)).content(bytes))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(PgWebhookInbox.Status.RECEIVED, row(e).getStatus()); assertEquals(PAYMENT_PROCESSING, state());
        assertEquals(1, webhookWorker.drain(20)); assertEquals(PAID, state()); assertEquals(PgWebhookInbox.Status.DONE, row(e).getStatus());
        assertEquals(1, pg.approvals.get()); assertEquals(1, pg.lookups.get()); assertEquals(0, webhookWorker.drain(20));
    }
    @Test void authenticatedClaimOfSuccessDoesNotMakeAnUnknownPgResultPaid() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.UNKNOWN; payments.approve(authentication, a.id());
        var e = event(); receive(e, a.id(), "APPROVED"); webhookWorker.drain(20);
        assertEquals(PAYMENT_PROCESSING, state()); assertEquals(PgWebhookInbox.Status.RETRY, row(e).getStatus());
        assertEquals("RESULT_UNKNOWN", row(e).getFailure()); assertEquals(1, pg.approvals.get());
    }
    @Test void duplicateEventIsAcknowledgedWithoutAnotherWorkItem() {
        var a = chargedUnknown(); var e = event();
        assertFalse(receive(e, a.id(), "APPROVED").duplicate()); assertTrue(receive(e, a.id(), "APPROVED").duplicate());
        webhookWorker.drain(20); assertTrue(receive(e, a.id(), "APPROVED").duplicate()); assertEquals(0, webhookWorker.drain(20));
        assertEquals(1, row(e).getAttempts()); assertEquals(1, pg.lookups.get());
    }
    @Test void freshInboxIsImmediatelyEligibleEvenWhenDatabaseRoundsNanoseconds() {
        var a=chargedUnknown();var e=event();
        var fixed=Clock.fixed(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456789),ZoneOffset.UTC);
        var fixedTransactions=new PgWebhookTransactions(inbox,stages,webhookSettings,fixed,manager);
        fixedTransactions.store("TEST_PG",merchantId,PaymentAttempt.digest(e),a.id(),PaymentAttempt.digest("test-body"),"tx-"+a.id());
        assertEquals(1,new PgWebhookWorker(inbox,fixedTransactions,payments,webhookSettings,fixed).drain(20));
        assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());assertEquals(PAID,state());
    }
    @Test void duplicateIdentityCannotBeReassignedToAnotherAttempt() {
        var a = ready(); var e = event(); receive(e, a.id(), "APPROVED");
        expect(409, () -> receive(e, UUID.randomUUID().toString(), "APPROVED")); assertEquals(a.id(), row(e).getAttemptId());
    }
    @Test void opaqueEventIdentityIsCaseSensitiveIndependentlyOfDatabaseCollation() {
        var a=chargedUnknown();String lower="event-a-"+event(),upper=lower.toUpperCase(Locale.ROOT);
        eventHashes.add(PaymentAttempt.digest(lower));eventHashes.add(PaymentAttempt.digest(upper));
        assertFalse(receive(lower,a.id(),"APPROVED").duplicate());assertFalse(receive(upper,a.id(),"APPROVED").duplicate());
        assertNotEquals(row(lower).getId(),row(upper).getId());webhookWorker.drain(20);assertEquals(PAID,state());assertEquals(1,pg.approvals.get());
    }
    @Test void delayedAndReversedAssertionsUseCurrentPgTruthAndDoNotReversePaid() {
        var a = chargedUnknown(); var later = event(); receive(later, a.id(), "FAILED"); webhookWorker.drain(20); assertEquals(PAID, state());
        var earlier = event(); receive(earlier, a.id(), "READY"); webhookWorker.drain(20); assertEquals(PAID, state());
        assertEquals(PgWebhookInbox.Status.DONE, row(earlier).getStatus()); assertEquals(1, pg.approvals.get());
    }
    @Test void badMissingDuplicateAndTamperedSignaturesCannotCreateInboxRows() throws Exception {
        var a = ready(); var e = event(); var bytes = body(e, a.id(), "APPROVED");
        mvc.perform(post("/payments/webhooks/TEST_PG").content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", "wrong").content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", FakeWebhookVerifier.signature(bytes), "wrong").content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", FakeWebhookVerifier.signature(bytes)).content(body(e, a.id(), "FAILED"))).andExpect(status().isUnauthorized());
        assertTrue(inbox.findByProviderAndEventKeyHash("TEST_PG", PaymentAttempt.digest(e)).isEmpty());
    }
    @Test void customerJwtCannotReplaceWebhookAuthenticationAndDoesNotBlockAValidSignature() throws Exception {
        var a = ready(); var bytes = body(event(), a.id(), "APPROVED");
        mvc.perform(post("/payments/webhooks/TEST_PG").header("Authorization", bearer).content(bytes)).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/webhooks/TEST_PG").header("Authorization", "Bearer malformed").header("X-Test-Signature", FakeWebhookVerifier.signature(bytes)).content(bytes))
                .andExpect(status().isOk());
    }
    @Test void unknownProviderInvalidReferenceAndOversizeBodyAreRejected() throws Exception {
        var bytes = body(event(), UUID.randomUUID().toString(), "APPROVED");
        mvc.perform(post("/payments/webhooks/UNKNOWN").content(bytes)).andExpect(status().isNotFound());
        var malformed = body(event(), "not-a-uuid", "APPROVED");
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", FakeWebhookVerifier.signature(malformed)).content(malformed)).andExpect(status().isBadRequest());
        mvc.perform(post("/payments/webhooks/TEST_PG").content(new byte[65537])).andExpect(status().is(413));
        mvc.perform(get("/payments/webhooks/TEST_PG")).andExpect(status().isUnauthorized());
    }
    @Test void actualInsertThenFailureRollsBackAndNeverAcknowledges() throws Exception {
        var a = ready(); var e = event(); var bytes = body(e, a.id(), "APPROVED");
        doAnswer(call -> { em.persist(call.getArgument(0)); em.flush(); throw new DataAccessResourceFailureException("injected inbox save failure"); }).when(inbox).saveAndFlush(any());
        mvc.perform(post("/payments/webhooks/TEST_PG").header("X-Test-Signature", FakeWebhookVerifier.signature(bytes)).content(bytes)).andExpect(status().isServiceUnavailable());
        reset(inbox); assertTrue(inbox.findByProviderAndEventKeyHash("TEST_PG", PaymentAttempt.digest(e)).isEmpty());
        assertFalse(receive(e, a.id(), "APPROVED").duplicate());
    }
    @Test void eightParallelReceiversUseOneUniqueEventAndRecoverAfterIndexConflict() throws Exception {
        var a = chargedUnknown(); var e = event(); var start = new CountDownLatch(1);
        var firstReads = new CountDownLatch(8); var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            var found = em.createQuery("select i from PgWebhookInbox i where i.provider=:provider and i.eventKeyHash=:hash", PgWebhookInbox.class)
                    .setParameter("provider", "TEST_PG").setParameter("hash", PaymentAttempt.digest(e)).getResultList().stream().findFirst();
            if (reads.incrementAndGet() <= 8) { firstReads.countDown(); await(firstReads); }
            return found;
        }).when(inbox).findByProviderAndMerchantIdAndEventKeyHash("TEST_PG",merchantId, PaymentAttempt.digest(e));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var requests = new ArrayList<Future<Boolean>>();
            for (int i=0;i<8;i++) requests.add(executor.submit(() -> { await(start); return receive(e, a.id(), "APPROVED").duplicate(); }));
            start.countDown(); int fresh=0; for(var f:requests) if(!f.get(10, TimeUnit.SECONDS)) fresh++;
            assertEquals(1, fresh); assertEquals(0, row(e).getAttempts());
        }
        webhookWorker.drain(20); assertEquals(PAID, state());
    }
    @Test void eightWorkersHaveOnlyOneLookupForTheLeasedEvent() throws Exception {
        var a = chargedUnknown(); var e = event(); receive(e, a.id(), "APPROVED");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); pg.beforeLookup = command -> { entered.countDown(); await(release); };
        try (var executor = Executors.newFixedThreadPool(8)) {
            var first=executor.submit(() -> webhookWorker.drain(20));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS)); var followers=new ArrayList<Future<Integer>>();
                for(int i=0;i<7;i++) followers.add(executor.submit(() -> webhookWorker.drain(20)));
                for(var f:followers) assertEquals(0,f.get(10,TimeUnit.SECONDS)); assertEquals(1,pg.lookups.get());
            } finally {release.countDown();}
            assertEquals(1,first.get(10,TimeUnit.SECONDS)); assertEquals(PAID,state());
        }
    }
    @Test void expiredLeaseCannotApplyFinancialResultOrCompleteANewerLease() {
        var a = chargedUnknown(); var e = event(); receive(e,a.id(),"APPROVED");
        var old=webhookTransactions.claim(row(e).getId()); var observation=payments.observeWebhook(a.id(),"TEST_PG");
        tx(() -> { ReflectionTestUtils.setField(em.find(PgWebhookInbox.class,old.id()),"leaseUntil",clock.instant().minusSeconds(1));return null;});
        var newer=webhookTransactions.claim(old.id()); assertNotEquals(old.token(),newer.token());
        expect(409,() -> webhookTransactions.apply(old,observation)); assertEquals(PAYMENT_PROCESSING,state());
        assertFalse(webhookTransactions.retry(old,"STALE"));
        webhookTransactions.apply(newer,observation); assertEquals(PAID,state()); assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());
    }
    @Test void paymentAndInboxCompletionRollbackTogetherAndCanBeRetried() {
        var a=chargedUnknown(); var e=event();receive(e,a.id(),"APPROVED");var lease=webhookTransactions.claim(row(e).getId());
        var observation=payments.observeWebhook(a.id(),"TEST_PG");
        doAnswer(call -> {em.flush();throw new DataAccessResourceFailureException("injected financial commit failure");}).when(attempts).flush();
        expect(503,() -> webhookTransactions.apply(lease,observation)); reset(attempts);
        assertEquals(PAYMENT_PROCESSING,state());assertEquals(PgWebhookInbox.Status.PROCESSING,row(e).getStatus());
        webhookTransactions.apply(lease,observation);assertEquals(PAID,state());assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());
    }
    @Test void inboxCompletionFailureAlsoRollsBackPayment() {
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");var lease=webhookTransactions.claim(row(e).getId());
        var observation=payments.observeWebhook(a.id(),"TEST_PG");
        doAnswer(call->{em.flush();throw new DataAccessResourceFailureException("injected inbox completion failure");}).when(inbox).flush();
        expect(503,()->webhookTransactions.apply(lease,observation));reset(inbox);
        assertEquals(PAYMENT_PROCESSING,state());assertEquals(PgWebhookInbox.Status.PROCESSING,row(e).getStatus());
    }
    @Test void retryBackoffAndExhaustionSurviveWorkerRecreation() {
        var a=ready();pg.mode=FakePgAdapter.Mode.UNKNOWN;payments.approve(authentication,a.id());var e=event();receive(e,a.id(),"APPROVED");
        assertEquals(1,webhookWorker.drain(20));assertEquals(0,webhookWorker.drain(20));assertTrue(row(e).getNextAt().isAfter(clock.instant()));
        due(e);new PgWebhookWorker(inbox,webhookTransactions,payments,webhookSettings,clock).drain(20);
        due(e);webhookWorker.drain(20);assertEquals(PgWebhookInbox.Status.FAILED,row(e).getStatus());assertEquals(3,row(e).getAttempts());
        assertEquals(0,webhookWorker.drain(20));assertEquals(PAYMENT_PROCESSING,state());assertEquals(1,pg.approvals.get());
    }
    @Test void unknownAttemptIsDurablyRetriedAndUnclaimedReadyCannotBePaidByWebhook() {
        var unknown=event();receive(unknown,UUID.randomUUID().toString(),"APPROVED");webhookWorker.drain(20);
        assertEquals(PgWebhookInbox.Status.RETRY,row(unknown).getStatus());
        var a=ready();var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);
        assertEquals(PENDING_PAYMENT,state());assertEquals(PgWebhookInbox.Status.RETRY,row(e).getStatus());assertEquals(0,pg.approvals.get());
    }
    @Test void cancelIntentSurvivesWebhookApprovalAndIsDispatchedByRecovery() {
        var a=chargedUnknown();orders.cancel(authentication,orderId);var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);
        assertEquals(CANCEL_PENDING,state());assertEquals(0,pg.cancels.get());recovery.drain(20);assertEquals(CANCELLED,state());
    }
    @Test void webhookCanCommitWhileTheOriginalApprovalResponseIsStillInFlight() throws Exception {
        var a=ready();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        pg.afterApproval=command->{entered.countDown();await(release);};
        try(var executor=Executors.newSingleThreadExecutor()){
            var approval=executor.submit(()->payments.approve(authentication,a.id()));
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));var e=event();receive(e,a.id(),"APPROVED");
                assertEquals(1,webhookWorker.drain(20));assertEquals(PAID,state());assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());
            }finally{release.countDown();}
            assertEquals(PaymentAttempt.Status.APPROVED,approval.get(10,TimeUnit.SECONDS).status());assertEquals(1,pg.approvals.get());
        }
    }
    @Test void cancellationWebhookLooksUpVoidWithoutSendingItAgain() {
        var a=chargedUnknown();orders.cancel(authentication,orderId);payments.reconcileApproval(a.id());
        pg.cancelMode=FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT;payments.processCancellation(a.id());assertEquals(CANCEL_PENDING,state());
        var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);
        assertEquals(CANCELLED,state());assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());assertEquals(1,pg.cancels.get());
    }
    @Test void financialMetadataMismatchRollsBackDoneAndRecordsConflict() {
        var a=chargedUnknown();pg.ledger.put(a.id(),new PgAdapter.ApprovalResult(a.id(),a.provider(),PgAdapter.ApprovalOutcome.APPROVED,"tx-"+a.id(),1,a.currency()));
        var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);
        assertEquals(PAYMENT_PROCESSING,state());assertEquals(PgWebhookInbox.Status.RETRY,row(e).getStatus());assertEquals("STATE_CONFLICT",row(e).getFailure());
    }
    @Test void authenticatedEventCannotCrossAStoredProviderBoundary() {
        var legacy=tx(()->{var a=new PaymentAttempt(em.find(ServiceOrder.class,orderId),"OTHER_PG",PaymentAttempt.digest("legacy"),clock.instant());em.persist(a);return a;});
        var e=event();expect(401,()->receive(e,legacy.getId(),"APPROVED"));
        assertEquals(PENDING_PAYMENT,state());assertEquals(0,pg.lookups.get());assertTrue(inbox.findByProviderAndEventKeyHash("TEST_PG",PaymentAttempt.digest(e)).isEmpty());
    }
    @Test void abandonedProcessingLeaseIsResumedByANewWorkerAfterExpiry() {
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");var lease=webhookTransactions.claim(row(e).getId());
        assertEquals(0,webhookWorker.drain(20));
        tx(()->{ReflectionTestUtils.setField(em.find(PgWebhookInbox.class,lease.id()),"leaseUntil",clock.instant().minusSeconds(1));return null;});
        assertEquals(1,new PgWebhookWorker(inbox,webhookTransactions,payments,webhookSettings,clock).drain(20));
        assertEquals(PAID,state());assertEquals(2,row(e).getAttempts());assertEquals(1,pg.approvals.get());
    }
    @Test void claimFailureRollsBackLeaseAndCrashAtFinalAttemptBecomesFailed() {
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");
        doAnswer(call->{em.flush();throw new DataAccessResourceFailureException("injected lease failure");}).when(inbox).flush();
        expect(503,()->webhookTransactions.claim(row(e).getId()));reset(inbox);assertEquals(0,row(e).getAttempts());assertNull(row(e).getLeaseToken());
        var lease=webhookTransactions.claim(row(e).getId());
        tx(()->{var i=em.find(PgWebhookInbox.class,lease.id());ReflectionTestUtils.setField(i,"attempts",3);ReflectionTestUtils.setField(i,"leaseUntil",clock.instant().minusSeconds(1));return null;});
        assertEquals(0,webhookWorker.drain(20));assertEquals(PgWebhookInbox.Status.FAILED,row(e).getStatus());assertEquals("ATTEMPTS_EXHAUSTED",row(e).getFailure());
    }
    @Test void webhookAndCustomerCancellationRacePreserveTheWinner() throws Exception {
        var a=chargedUnknown();var e=event();receive(e,a.id(),"APPROVED");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var result=executor.submit(()->{await(start);return webhookWorker.drain(20);});
            var cancel=executor.submit(()->{await(start);try{return orders.cancel(authentication,orderId).accepted()?202:200;}catch(org.springframework.web.server.ResponseStatusException error){return error.getStatusCode().value();}});
            start.countDown();assertEquals(1,result.get(10,TimeUnit.SECONDS));int code=cancel.get(10,TimeUnit.SECONDS);
            if(code==202){assertEquals(CANCEL_PENDING,state());assertTrue(view(a.id()).cancelRequested());}else{assertEquals(409,code);assertEquals(PAID,state());}
            assertEquals(PgWebhookInbox.Status.DONE,row(e).getStatus());assertEquals(1,pg.approvals.get());
        }
    }
    @Test void providerDisabledSiteCurrencyAndAmountLimitsPreventNewRows() {
        var rule=providerSettings.getRules().get("TEST_PG");rule.setEnabled(false);expect(503,this::ready);
        rule.setEnabled(true);rule.setAllowAllSites(false);expect(409,this::ready);
        rule.setAllowAllSites(true);rule.setCurrencies(new HashSet<>(Set.of("USD")));expect(409,this::ready);
        rule.setCurrencies(new HashSet<>(Set.of("KRW")));rule.setMaximumAmount(100);expect(409,this::ready);
        rule.setMaximumAmount(Integer.MAX_VALUE);rule.setMinimumAmount(3000);expect(409,this::ready);assertEquals(0,attempts.count());
    }
    @Test void configuredProviderWithoutAdapterCannotCreateReady() {
        var rule=new PaymentProviderProperties.Rule();rule.setEnabled(true);rule.setAllowAllSites(true);providerSettings.getRules().put("OTHER_PG",rule);
        expect(503,()->payments.createAttempt(authentication,orderId,"OTHER_PG","missing-adapter"));assertEquals(0,attempts.count());
    }
    @Test void legacyUnregisteredReadyCanBeReadButCannotBeApproved() {
        var legacy=tx(()->{var a=new PaymentAttempt(em.find(ServiceOrder.class,orderId),"NAVER_PAY",PaymentAttempt.digest("legacy"),clock.instant());em.persist(a);return a;});
        assertEquals(legacy.getId(),payments.createAttempt(authentication,orderId,"NAVER_PAY","legacy").id());
        expect(503,()->payments.approve(authentication,legacy.getId()));
        assertEquals(PENDING_PAYMENT,state());assertEquals(PaymentAttempt.Status.READY,view(legacy.getId()).status());assertEquals(0,pg.approvals.get());
    }
    @Test void explicitSiteAllowlistCanAuthorizeThisOrder() {
        var rule=providerSettings.getRules().get("TEST_PG");rule.setAllowAllSites(false);rule.getSiteIds().add(siteId);
        assertEquals(PaymentAttempt.Status.READY,ready().status());
    }
    @Test void providerDisabledAfterCreationBlocksNewApprovalButPreservesReplayAndFinancialRecovery() {
        var a=ready();providerSettings.getRules().get("TEST_PG").setEnabled(false);
        assertEquals(a.id(),ready().id());expect(503,()->payments.approve(authentication,a.id()));assertEquals(0,pg.approvals.get());
        providerSettings.getRules().get("TEST_PG").setEnabled(true);pg.mode=FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT;payments.approve(authentication,a.id());
        providerSettings.getRules().get("TEST_PG").setEnabled(false);var e=event();receive(e,a.id(),"APPROVED");webhookWorker.drain(20);assertEquals(PAID,state());
    }
}
