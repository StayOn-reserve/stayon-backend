package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.PaymentRecoveryProperties;
import com.stayon.stayon_backend.dto.customer.*;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.security.JwtTokenProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The inherited financial race suite also runs under the new API/recovery configuration. */
@TestPropertySource(properties = {"payment.recovery.initial-grace=0s", "payment.recovery.base-backoff=1s", "payment.recovery.max-attempts=3"})
class CustomerPaymentRecoveryTests extends PaymentFlowTests {
    @Autowired protected CustomerPaymentService api;
    @Autowired protected PaymentRecoveryWorker recovery;
    @Autowired protected PaymentRecoveryProperties recoverySettings;
    @Autowired protected Clock clock;
    @Autowired private JwtTokenProvider adminTokens;
    private String createPath() { return "/customer/orders/" + orderId + "/payment-attempts"; }
    private String approvePath(String id) { return "/customer/payment-attempts/" + id + "/approve"; }
    private String statusPath(String id) { return "/customer/payment-attempts/" + id; }
    private void due(String id) {
        tx(() -> { var a = em.find(PaymentAttempt.class, id);
            ReflectionTestUtils.setField(a, "recoveryNextAt", clock.instant().minusSeconds(1)); return null; });
    }
    private PaymentAttempt stored(String id) { return tx(() -> em.find(PaymentAttempt.class, id)); }
    @Test void apiCreateReplayApproveAndReadUseOnlyPublicServerSnapshot() throws Exception {
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("amount").value(2400)).andExpect(jsonPath("currency").value("KRW"));
        var a = payments.createAttempt(authentication, orderId, pg.provider(), "api-key");
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "api-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("id").value(a.id()));
        for (int i = 0; i < 2; i++) mvc.perform(post(approvePath(a.id())).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("APPROVED"))
                .andExpect(jsonPath("transactionId").doesNotExist()).andExpect(jsonPath("approvalClaim").doesNotExist())
                .andExpect(jsonPath("recoveryToken").doesNotExist());
        mvc.perform(get(statusPath(a.id())).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("amount").value(2400)).andExpect(jsonPath("pgTransactionId").doesNotExist());
        assertEquals(1, pg.approvals.get());
    }
    @ParameterizedTest @ValueSource(strings = {"amount", "currency", "reservationId", "status", "transactionId"})
    void apiRejectsClientControlledFinancialFields(String field) throws Exception {
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "tamper")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\",\"" + field + "\":1}"))
                .andExpect(status().isBadRequest());
        assertEquals(0L, tx(() -> em.createQuery("select count(a) from PaymentAttempt a where a.serviceOrder.id=:id", Long.class).setParameter("id", orderId).getSingleResult()));
    }
    @Test void apiRequiresValidCreationKeyAndDetectsProviderConflict() throws Exception {
        mvc.perform(post(createPath()).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"TEST_PG\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "bad key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\"}")).andExpect(status().isBadRequest());
        ready();
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "attempt-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"OTHER_PG\"}")).andExpect(status().isConflict());
    }
    @Test void apiApprovalResponseDistinguishesPendingAndDefinitiveFailure() throws Exception {
        var a = ready(); pg.mode = FakePgAdapter.Mode.UNKNOWN;
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isAccepted())
                .andExpect(jsonPath("status").value("UNKNOWN"));
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isAccepted());
        assertEquals(1, pg.approvals.get());
        stages.applyApproval(a.id(), view(a.id()).approvalClaim(), result(a, PgAdapter.ApprovalOutcome.DECLINED));
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isConflict());
    }
    @Test void unsupportedBrowserProofIsRejectedBeforeClaimWithoutPersistingIt() throws Exception {
        var a = ready();
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"authentication\":{\"version\":\"provider-v1\",\"payload\":{\"opaqueCode\":\"test-proof\"}}}"))
                .andExpect(status().isBadRequest());
        assertEquals(PaymentAttempt.Status.READY, view(a.id()).status()); assertEquals(0, pg.approvals.get());
        assertFalse(new PgAdapter.BrowserAuthentication("v1", Map.of("secret", "test-proof")).toString().contains("test-proof"));
    }
    @Test void approvalCannotAcceptAmountOrUnboundedBrowserCredentials() throws Exception {
        var a = ready();
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1}")).andExpect(status().isBadRequest());
        expect(400, () -> api.approve(authentication, a.id(), new CustomerPaymentApproveRequest(
                new CustomerPaymentApproveRequest.BrowserProof("v1", Map.of("code", "x".repeat(2049))))));
        assertEquals(0, pg.approvals.get());
    }
    @Test void providerSpecificTransientBrowserContractCanBeExtendedWithoutChangingTheApi() throws Exception {
        var a = ready(); pg.browserEnabled = true;
        var response = mvc.perform(post(approvePath(a.id())).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"authentication\":{\"version\":\"TEST_V1\",\"payload\":{\"opaqueCode\":\"ephemeral-test-proof\"}}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("ephemeral-test-proof")); assertFalse(response.contains("payload")); assertEquals(1, pg.approvals.get());
    }
    @Test void unsupportedProviderCreationReturns400WithoutSavingReady() throws Exception {
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "unconfigured")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"NAVER_PAY\"}")).andExpect(status().isBadRequest());
        assertEquals(0, attempts.count()); assertEquals(0, pg.approvals.get());
    }
    @Test void approvalHttpRequestAndCancellationRetainTheExisting202Contract() throws Exception {
        var a = ready(); pg.block();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var approval = executor.submit(() -> mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isAccepted()));
            try {
                assertTrue(pg.entered.await(5, TimeUnit.SECONDS));
                mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("CANCEL_PENDING"));
                mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isAccepted());
            } finally { pg.release.countDown(); }
            approval.get(10, TimeUnit.SECONDS); assertEquals(1, pg.approvals.get());
            recovery.drain(20); assertEquals(CANCELLED, state());
        }
    }
    @Test void paymentReadDatabaseErrorsAre503() throws Exception {
        var a = ready();
        doThrow(new DataAccessResourceFailureException("injected read failure")).when(attempts).findByIdAndServiceOrder_Reservation_Id(a.id(), reservationId);
        mvc.perform(get(statusPath(a.id())).header("Authorization", bearer)).andExpect(status().isServiceUnavailable());
    }
    @Test void foreignReservationCannotReadListOrApproveAttempt() throws Exception {
        var a = ready(); var foreign = session(foreignReservationId, Set.of("orders:read", "orders:create"));
        String authorization = "Bearer " + tokens.createAccessToken(foreign);
        mvc.perform(get(statusPath(a.id())).header("Authorization", authorization)).andExpect(status().isNotFound());
        mvc.perform(get(createPath()).header("Authorization", authorization)).andExpect(status().isNotFound());
        mvc.perform(post(approvePath(a.id())).header("Authorization", authorization)).andExpect(status().isNotFound());
        mvc.perform(post(createPath()).header("Authorization", authorization).header("Idempotency-Key", "foreign")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\"}")).andExpect(status().isNotFound());
        assertEquals(0, pg.approvals.get());
    }
    @Test void missingExpiredAdminAndRevokedTokensCannotUsePaymentApi() throws Exception {
        var a = ready();
        mvc.perform(get(statusPath(a.id()))).andExpect(status().isUnauthorized());
        var expired = new CustomerAuthStore.Session(UUID.randomUUID().toString(), reservationId, "unused", Set.of("orders:read"), Instant.now().minusSeconds(60).getEpochSecond());
        mvc.perform(get(statusPath(a.id())).header("Authorization", "Bearer " + tokens.createAccessToken(expired))).andExpect(status().isUnauthorized());
        mvc.perform(get(statusPath(a.id())).header("Authorization", "Bearer " + adminTokens.createAccessToken(1L, "MANAGER"))).andExpect(status().isUnauthorized());
        when(sessions.findSession(anyString())).thenReturn(Optional.empty());
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }
    @Test void scopeContactAndRedisErrorsAreEnforcedBeforeReplay() throws Exception {
        var a = ready(); var read = session(reservationId, Set.of("orders:read"));
        mvc.perform(post(approvePath(a.id())).header("Authorization", "Bearer " + tokens.createAccessToken(read))).andExpect(status().isForbidden());
        var write = session(reservationId, Set.of("orders:create"));
        mvc.perform(get(statusPath(a.id())).header("Authorization", "Bearer " + tokens.createAccessToken(write))).andExpect(status().isForbidden());
        tx(() -> { em.find(Reservation.class, reservationId).setCustomerPhone("01098765432"); return null; });
        mvc.perform(get(statusPath(a.id())).header("Authorization", bearer)).andExpect(status().isUnauthorized());
        when(sessions.findSession(anyString())).thenThrow(new DataAccessResourceFailureException("test redis unavailable"));
        mvc.perform(post(approvePath(a.id())).header("Authorization", bearer)).andExpect(status().isServiceUnavailable());
        assertEquals(0, pg.approvals.get());
    }
    @Test void paymentHistoryIsPaginatedAndReadableAfterCheckoutWithoutPrivateFields() throws Exception {
        String newest = null;
        for (int i = 0; i < 3; i++) {
            var a = payments.createAttempt(authentication, orderId, pg.provider(), "history-" + i);
            newest = a.id();
            pg.mode = FakePgAdapter.Mode.DECLINED; payments.approve(authentication, a.id());
        }
        tx(() -> { var r = em.find(Reservation.class, reservationId); r.setCheckOutDate(LocalDate.now(ZoneId.of("Asia/Seoul"))); r.setSite(null); return null; });
        mvc.perform(get(createPath()).header("Authorization", bearer).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("content[0].id").value(newest));
        mvc.perform(get(createPath()).header("Authorization", bearer).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(3)).andExpect(jsonPath("content.length()").value(1))
                .andExpect(jsonPath("content[0].idempotencyKeyHash").doesNotExist());
        mvc.perform(get(createPath()).header("Authorization", bearer).param("size", "101")).andExpect(status().isBadRequest());
    }
    @Test void emptyHistoryIsBoundedAndCreationStillRequiresOrderableReservation() throws Exception {
        mvc.perform(get(createPath()).header("Authorization", bearer)).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0));
        tx(() -> { em.find(Reservation.class, reservationId).setEventType(ReservationEventType.CANCELLED); return null; });
        mvc.perform(post(createPath()).header("Authorization", bearer).header("Idempotency-Key", "closed")
                .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"TEST_PG\"}")).andExpect(status().isForbidden());
    }
    @Test void workerRecoversUnknownByLookupWithoutRepeatingApproval() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id());
        assertEquals(1, recovery.drain(20)); assertEquals(PAID, state()); assertEquals(1, pg.approvals.get()); assertEquals(1, pg.lookups.get());
        assertEquals(0, recovery.drain(20));
    }
    @Test void workerRecoversApprovalCommittedAtPgButRolledBackLocally() {
        var a = ready(); pg.afterApproval = command -> doAnswer(call -> { em.flush(); throw new DataAccessResourceFailureException("injected commit failure"); }).when(attempts).flush();
        expect(503, () -> payments.approve(authentication, a.id())); reset(attempts);
        assertEquals(PAYMENT_PROCESSING, state()); assertEquals(1, recovery.drain(20));
        assertEquals(PAID, state()); assertEquals(1, pg.approvals.get());
    }
    @Test void financialRecoveryDoesNotDependOnAnExpiredOrUnavailableCustomerSession() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id());
        when(sessions.findSession(anyString())).thenThrow(new DataAccessResourceFailureException("customer session unavailable"));
        assertEquals(1, recovery.drain(20)); assertEquals(PAID, state()); assertEquals(1, pg.approvals.get());
    }
    @Test void lookupTransportFailureBacksOffThenRecoversWithoutAnotherApproval() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id());
        pg.beforeLookup = command -> { throw new IllegalStateException("simulated lookup timeout"); };
        assertEquals(1, recovery.drain(20)); assertEquals(PaymentAttempt.Status.UNKNOWN, view(a.id()).status());
        assertNotNull(stored(a.id()).getRecoveryNextAt());
        due(a.id()); pg.beforeLookup = command -> { }; recovery.drain(20);
        assertEquals(PAID, state()); assertEquals(1, pg.approvals.get()); assertEquals(2, pg.lookups.get());
    }
    @Test void unknownLookupBackoffBudgetAndFailureSurviveWorkerRecreation() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.UNKNOWN; payments.approve(authentication, a.id());
        assertEquals(1, recovery.drain(20)); var first = stored(a.id());
        assertEquals(1, first.getRecoveryAttempts()); assertEquals("RESULT_UNKNOWN", first.getRecoveryFailure());
        assertTrue(first.getRecoveryNextAt().isAfter(clock.instant())); assertEquals(0, recovery.drain(20));
        due(a.id()); new PaymentRecoveryWorker(attempts, stages, payments, recoverySettings, clock).drain(20);
        due(a.id()); recovery.drain(20); due(a.id()); assertEquals(0, recovery.drain(20));
        var exhausted = stored(a.id()); assertEquals(3, exhausted.getRecoveryAttempts());
        assertEquals("EXHAUSTED_RESULT_UNKNOWN", exhausted.getRecoveryFailure()); assertNotNull(exhausted.getRecoveryFailedAt());
        assertEquals(PaymentAttempt.Status.UNKNOWN, exhausted.getStatus()); assertEquals(1, pg.approvals.get());
    }
    @Test void cancellationTimeoutIsRetriedAsLookupAndNotAnotherFinancialVoid() {
        var a = ready(); stages.claimApproval(authentication, a.id()); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), view(a.id()).approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED));
        pg.cancelMode = FakePgAdapter.Mode.TIMEOUT;
        assertEquals(1, recovery.drain(20)); assertEquals(1, pg.cancels.get()); assertEquals(CANCEL_PENDING, state());
        pg.cancelLedger.put(a.id(), new PgAdapter.CancelResult(a.id(), a.provider(), PgAdapter.CancelOutcome.CANCELED, view(a.id()).transactionId(), a.amount(), a.currency()));
        due(a.id()); assertEquals(1, recovery.drain(20)); assertEquals(CANCELLED, state()); assertEquals(1, pg.cancels.get());
    }
    @Test void recoveryCarriesPersistedCancelIntentThroughApprovalAndVoid() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id()); orders.cancel(authentication, orderId);
        assertEquals(1, recovery.drain(20)); assertEquals(CANCEL_PENDING, state()); assertEquals(0, pg.cancels.get());
        assertEquals(1, recovery.drain(20)); assertEquals(CANCELLED, state()); assertEquals(1, pg.cancels.get());
    }
    @Test void eightWorkersHaveOneLookupClaimWhileTheFirstLookupIsInFlight() throws Exception {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT; payments.approve(authentication, a.id());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        pg.beforeLookup = command -> { entered.countDown(); await(release); };
        try (var executor = Executors.newFixedThreadPool(8)) {
            var first = executor.submit(() -> recovery.drain(20));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var followers = new ArrayList<Future<Integer>>();
                for (int i = 0; i < 7; i++) followers.add(executor.submit(() -> recovery.drain(20)));
                for (var f : followers) assertEquals(0, f.get(10, TimeUnit.SECONDS));
                assertEquals(1, pg.lookups.get());
            } finally { release.countDown(); }
            assertEquals(1, first.get(10, TimeUnit.SECONDS)); assertEquals(PAID, state());
        }
    }
    @Test void expiredLeaseCanBeReclaimedAfterRestartButOldResultCannotCommit() {
        var a = ready(); stages.claimApproval(authentication, a.id());
        var old = stages.claimRecovery(a.id(), recoverySettings); assertNotNull(old);
        tx(() -> { ReflectionTestUtils.setField(em.find(PaymentAttempt.class, a.id()), "recoveryLeaseUntil", clock.instant().minusSeconds(1)); return null; });
        var newer = stages.claimRecovery(a.id(), recoverySettings); assertNotNull(newer); assertNotEquals(old.token(), newer.token());
        expect(409, () -> stages.applyApproval(a.id(), view(a.id()).approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED), old.token()));
        assertFalse(stages.finishRecovery(old, Duration.ofSeconds(1), "STALE"));
        stages.applyApproval(a.id(), view(a.id()).approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED), newer.token());
        stages.finishRecovery(newer, Duration.ofSeconds(1), "RESULT_UNKNOWN"); assertEquals(PAID, state());
    }
    @Test void eightRecoveryWorkersDispatchOnlyOneVoid() throws Exception {
        var a = ready(); stages.claimApproval(authentication, a.id()); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), view(a.id()).approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED)); pg.blockCancel();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var first = executor.submit(() -> recovery.drain(20));
            try {
                assertTrue(pg.cancelEntered.await(5, TimeUnit.SECONDS));
                var followers = new ArrayList<Future<Integer>>();
                for (int i = 0; i < 7; i++) followers.add(executor.submit(() -> recovery.drain(20)));
                for (var f : followers) assertEquals(0, f.get(10, TimeUnit.SECONDS));
                assertEquals(1, pg.cancels.get());
            } finally { pg.cancelRelease.countDown(); }
            assertEquals(1, first.get(10, TimeUnit.SECONDS)); assertEquals(CANCELLED, state());
        }
    }
    @Test void recoveryClaimFailureRollsBackCounterAndLeaseBeforeLookup() {
        var a = ready(); stages.claimApproval(authentication, a.id());
        doThrow(new DataAccessResourceFailureException("claim failed")).when(attempts).flush();
        expect(503, () -> stages.claimRecovery(a.id(), recoverySettings)); reset(attempts);
        assertEquals(0, stored(a.id()).getRecoveryAttempts()); assertNull(stored(a.id()).getRecoveryToken()); assertEquals(0, pg.lookups.get());
        assertNotNull(stages.claimRecovery(a.id(), recoverySettings));
    }
    @Test void recoveryLeaseCompletionFailureIsRecoverableAfterExpiry() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.UNKNOWN; payments.approve(authentication, a.id());
        var lease = stages.claimRecovery(a.id(), recoverySettings);
        doThrow(new DataAccessResourceFailureException("completion failed")).when(attempts).flush();
        expect(503, () -> stages.finishRecovery(lease, Duration.ofSeconds(1), "RESULT_UNKNOWN")); reset(attempts);
        assertEquals(lease.token(), stored(a.id()).getRecoveryToken()); assertEquals(0, recovery.drain(20));
        tx(() -> { ReflectionTestUtils.setField(em.find(PaymentAttempt.class, a.id()), "recoveryLeaseUntil", clock.instant().minusSeconds(1)); return null; });
        assertEquals(1, recovery.drain(20)); assertEquals(2, stored(a.id()).getRecoveryAttempts());
    }
    @Test void recoveryHonorsInitialGraceAndRefusesOuterTransactions() {
        var a = ready(); stages.claimApproval(authentication, a.id());
        var settings = new PaymentRecoveryProperties(); assertNull(stages.claimRecovery(a.id(), settings));
        assertThrows(IllegalStateException.class, () -> tx(() -> recovery.drain(20)));
        assertEquals(Duration.ofSeconds(5), settings.backoff(1)); assertEquals(Duration.ofMinutes(15), settings.backoff(100));
        assertFalse(settings.isEnabled());
    }
}
