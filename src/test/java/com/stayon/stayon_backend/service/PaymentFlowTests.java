package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.entity.Role;
import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import com.stayon.stayon_backend.repository.*;
import com.stayon.stayon_backend.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Import(PaymentFlowTests.Gateways.class)
@Timeout(30)
class PaymentFlowTests {
    @TestConfiguration(proxyBeanMethods = false) static class Gateways {
        @Bean @Profile("test") FakePgAdapter fakePg() { return new FakePgAdapter(); }
        @Bean @Profile("test") FakePgSecretResolver fakeSecrets(){return new FakePgSecretResolver();}
    }
    @Autowired protected EntityManager em;
    @Autowired protected PlatformTransactionManager manager;
    @Autowired protected CustomerOrderService orders;
    @Autowired protected PaymentOrchestrator payments;
    @Autowired protected PaymentTransactions stages;
    @Autowired protected PaymentCancellationWorker worker;
    @Autowired protected PgAdapters registry;
    @Autowired protected PgSecrets pgSecrets;
    @Autowired protected FakePgAdapter pg;
    @Autowired protected MockMvc mvc;
    @Autowired protected CustomerJwtTokenProvider tokens;
    @Autowired protected CustomerAuthSecrets secrets;
    @MockitoBean protected CustomerAuthStore sessions;
    @MockitoSpyBean protected PaymentAttemptRepository attempts;
    protected Long reservationId, foreignReservationId, siteId, productId, orderId,ownerId,accommodationId,itemId;
    protected String merchantId;
    protected Authentication authentication, foreignAuthentication;
    protected String bearer;
    private final Map<String, CustomerAuthStore.Session> sessionRows = new ConcurrentHashMap<>();
    protected <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(status -> work.get()); }

    @BeforeEach void setup() {
        pg.reset();
        tx(() -> {
            var owner=User.builder().email(UUID.randomUUID()+"@fixture.invalid").password("test-only-unused").name("Fixture owner").role(Role.MANAGER).build();em.persist(owner);ownerId=owner.getUserId();
            var accommodation=new Accommodation("Payment fixture",Platform.CAMFIT,owner);em.persist(accommodation);accommodationId=accommodation.getAccommodationId();
            var item=new AccommodationItem(UUID.randomUUID().toString(),accommodation,"Payment item");em.persist(item);itemId=item.getId();
            var site = new Site("payment fixture");site.assignAccommodationItem(item); em.persist(site); siteId = site.getId();
            var merchant=new PgMerchant(owner,"TEST_PG",PgMerchant.Environment.SANDBOX,"test-mid-"+UUID.randomUUID(),Set.of("KRW"),"placeholder",Instant.now());
            merchantId=merchant.getId();ReflectionTestUtils.setField(merchant,"secretReference","test/"+ownerId+"/TEST_PG/"+merchantId);em.persist(merchant);
            var r = reservation(site); em.persist(r); reservationId = r.getId();
            var other = reservation(site); em.persist(other); foreignReservationId = other.getId();
            var p = new AdditionalService(site, "Stored amount", 1200, AdditionalService.ServiceInputType.QUANTITY, true, null, null, null);
            em.persist(p); productId = p.getId(); return null;
        });
        when(sessions.findSession(anyString())).thenAnswer(call -> Optional.ofNullable(sessionRows.get(call.getArgument(0))));
        var own = session(reservationId, Set.of("orders:read", "orders:create", "orders:cancel"));
        authentication = auth(own); bearer = "Bearer " + tokens.createAccessToken(own);
        foreignAuthentication = auth(session(foreignReservationId, own.scopes()));
        orderId = orders.create(authentication, "fixture", request()).order().id();
        clearInvocations(attempts);
    }
    @AfterEach void cleanup() {
        pg.release.countDown(); pg.cancelRelease.countDown();
        tx(() -> {
            var ids = List.of(reservationId, foreignReservationId);
            em.createQuery("delete PaymentAttempt a where a.serviceOrder.reservation.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete ServiceOrderItem i where i.serviceOrder.reservation.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete ServiceOrder o where o.reservation.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete AdditionalService s where s.site.id=:id").setParameter("id", siteId).executeUpdate();
            em.createQuery("delete Reservation r where r.id in :ids").setParameter("ids", ids).executeUpdate();
            em.remove(em.find(Site.class, siteId)); return null;
        });
        tx(()->{
            em.createNativeQuery("delete from pg_merchant_currency where merchant_id in (select id from pg_merchant where owner_id=:id)").setParameter("id",ownerId).executeUpdate();
            em.createQuery("delete PgMerchant m where m.owner.userId=:id").setParameter("id",ownerId).executeUpdate();
            em.remove(em.find(AccommodationItem.class,itemId));em.remove(em.find(Accommodation.class,accommodationId));em.remove(em.find(User.class,ownerId));return null;
        });
    }
    private Reservation reservation(Site site) {
        var r = new Reservation(); r.setPlatform(Platform.CAMFIT); r.setReservationId(UUID.randomUUID().toString());
        r.setEventType(ReservationEventType.RESERVED); r.setSyncStatus("SUCCESS"); r.setSite(site); r.setCustomerPhone("01012345678");
        var today = LocalDate.now(ZoneId.of("Asia/Seoul")); r.setCheckInDate(today.minusDays(1)); r.setCheckOutDate(today.plusDays(1)); r.setNights(2); return r;
    }
    protected CustomerOrderCreateRequest request() { return new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(productId, 2))); }
    protected CustomerAuthStore.Session session(Long id, Set<String> scopes) {
        var s = new CustomerAuthStore.Session(UUID.randomUUID().toString(), id, secrets.hmac("phone", "01012345678"), scopes, Instant.now().getEpochSecond() + 900);
        sessionRows.put(s.id(), s); return s;
    }
    protected Authentication auth(CustomerAuthStore.Session s) { return new UsernamePasswordAuthenticationToken(new CustomerPrincipal(s.reservationId(), s.id(), s.scopes()), null, List.of(new SimpleGrantedAuthority("CUSTOMER"))); }
    protected PaymentTransactions.View ready() { return payments.createAttempt(authentication, orderId, pg.provider(), "attempt-key"); }
    protected ServiceOrder.ServiceOrderStatus state() { return tx(() -> em.find(ServiceOrder.class, orderId).getStatus()); }
    protected PaymentTransactions.View view(String id) { return stages.inspectInternal(id); }
    protected void expect(int code, org.junit.jupiter.api.function.Executable action) { assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value()); }
    protected PgAdapter.ApprovalResult result(PaymentTransactions.View v, PgAdapter.ApprovalOutcome outcome) {
        return new PgAdapter.ApprovalResult(v.id(), v.provider(), outcome, outcome == PgAdapter.ApprovalOutcome.APPROVED ? "tx-" + v.id() : null, v.amount(), v.currency(),v.merchant()==null?null:v.merchant().externalId());
    }

    @Test void readyReplayAndSuccessfulApprovalUseStoredAmountAndOneExternalCall() {
        var a = ready(); assertEquals(2400, a.amount()); assertEquals("KRW", a.currency());
        assertEquals(a.id(), ready().id());
        tx(() -> { em.find(AdditionalService.class, productId).update("Changed", 9999, AdditionalService.ServiceInputType.QUANTITY, false, null, null, null); return null; });
        var approved = payments.approve(authentication, a.id()); assertEquals(PaymentAttempt.Status.APPROVED, approved.status());
        assertEquals(PAID, state()); assertEquals(2400, pg.ledger.get(a.id()).amount());
        assertEquals(approved, payments.approve(authentication, a.id())); assertEquals(1, pg.approvals.get());
        expect(409, () -> orders.cancel(authentication, orderId));
    }
    @Test void activeSlotRejectsAnotherAttemptAndDefinitiveFailureAllowsANewKey() {
        var a = ready(); expect(409, () -> payments.createAttempt(authentication, orderId, pg.provider(), "other-key"));
        pg.mode = FakePgAdapter.Mode.DECLINED;
        assertEquals(PaymentAttempt.Status.FAILED, payments.approve(authentication, a.id()).status()); assertEquals(PENDING_PAYMENT, state());
        pg.mode = FakePgAdapter.Mode.SUCCESS;
        var b = payments.createAttempt(authentication, orderId, pg.provider(), "retry-key"); assertNotEquals(a.id(), b.id());
        assertEquals(PaymentAttempt.Status.APPROVED, payments.approve(authentication, b.id()).status());
        assertEquals(PaymentAttempt.Status.FAILED, payments.approve(authentication, a.id()).status()); assertEquals(2, pg.approvals.get());
    }
    @Test void readyCancellationNeverCallsPgOrApprovesAfterwards() {
        var a = ready(); assertFalse(orders.cancel(authentication, orderId).accepted()); assertEquals(CANCELLED, state());
        assertEquals(PaymentAttempt.Status.CANCELED, payments.approve(authentication, a.id()).status()); assertEquals(0, pg.approvals.get());
        assertEquals(a.id(), ready().id());
    }
    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "DECLINED", "UNKNOWN", "TIMEOUT"})
    void cancellationDuringNetworkCallIsDurableAndNeverOverwritten(String mode) throws Exception {
        var a = ready(); pg.mode = FakePgAdapter.Mode.valueOf(mode); pg.block();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var approval = executor.submit(() -> payments.approve(authentication, a.id()));
            try {
                assertTrue(pg.entered.await(5, TimeUnit.SECONDS)); assertEquals(PAYMENT_PROCESSING, state());
                mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCEL_PENDING"));
                mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isAccepted());
                assertTrue(view(a.id()).cancelRequested()); assertEquals(0, pg.cancels.get());
            } finally { pg.release.countDown(); }
            var end = approval.get(10, TimeUnit.SECONDS);
            if (mode.equals("SUCCESS")) {
                assertEquals(PaymentAttempt.Status.CANCEL_PENDING, end.status()); assertEquals(CANCEL_PENDING, state());
                assertEquals(1, worker.drain(20)); assertEquals(CANCELLED, state()); assertEquals(1, pg.cancels.get());
                assertEquals(0, worker.drain(20));
                mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isOk());
            } else if (mode.equals("DECLINED")) {
                assertEquals(PaymentAttempt.Status.FAILED, end.status()); assertEquals(CANCELLED, state()); assertEquals(0, pg.cancels.get());
            } else {
                assertEquals(PaymentAttempt.Status.UNKNOWN, end.status()); assertEquals(CANCEL_PENDING, state()); assertEquals(0, worker.drain(20));
                expect(409, () -> payments.createAttempt(authentication, orderId, pg.provider(), "unsafe-retry"));
            }
        }
    }
    @Test void timeoutAfterPgSuccessUsesLookupAndNeverReapproves() {
        var a = ready(); pg.mode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT;
        assertEquals(PaymentAttempt.Status.UNKNOWN, payments.approve(authentication, a.id()).status());
        assertEquals(PAYMENT_PROCESSING, state()); assertEquals(PaymentAttempt.Status.UNKNOWN, payments.approve(authentication, a.id()).status());
        assertEquals(1, pg.approvals.get());
        assertEquals(PaymentAttempt.Status.APPROVED, new PaymentOrchestrator(stages, registry,pgSecrets).reconcileApproval(a.id()).status()); assertEquals(PAID, state());
    }
    @ParameterizedTest @ValueSource(strings = {"APPROVED", "DECLINED"})
    void unknownAfterCancelNeedsAuthoritativeLookupBeforeFinalCancellation(String outcome) {
        var a = ready(); var claimed = stages.claimApproval(authentication, a.id()).view();
        orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), claimed.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.UNKNOWN));
        assertEquals(CANCEL_PENDING, state()); assertEquals(0, worker.drain(20));
        pg.ledger.put(a.id(), result(a, PgAdapter.ApprovalOutcome.valueOf(outcome)));
        payments.reconcileApproval(a.id());
        if (outcome.equals("APPROVED")) { assertEquals(CANCEL_PENDING, state()); worker.drain(20); }
        assertEquals(CANCELLED, state());
    }
    @Test void duplicateAndLateResultsCannotReverseTerminalStates() {
        var a = ready(); var claimed = stages.claimApproval(authentication, a.id()).view(); orders.cancel(authentication, orderId);
        var approval = result(a, PgAdapter.ApprovalOutcome.APPROVED);
        stages.applyApproval(a.id(), claimed.approvalClaim(), approval); worker.drain(20);
        assertEquals(CANCELLED, state());
        assertEquals(PaymentAttempt.Status.CANCELED, stages.applyApproval(a.id(), claimed.approvalClaim(), approval).status());
        stages.applyApproval(a.id(), claimed.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.UNKNOWN));
        expect(409, () -> stages.applyApproval(a.id(), claimed.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.DECLINED)));
        expect(409, () -> stages.applyApproval(a.id(), "wrong-claim", approval)); assertEquals(CANCELLED, state());
    }
    @ParameterizedTest @ValueSource(strings = {"beforeFlush", "afterFlush"})
    void pgSuccessWithDbFailureRetainsClaimAndCanBeRecoveredAfterRestart(String point) {
        var a = ready();
        pg.afterApproval = command -> doAnswer(call -> {
            if (point.equals("afterFlush")) em.flush();
            throw new DataAccessResourceFailureException("injected result commit failure");
        }).when(attempts).flush();
        expect(503, () -> payments.approve(authentication, a.id()));
        reset(attempts); assertEquals(PAYMENT_PROCESSING, state()); assertEquals(PaymentAttempt.Status.APPROVING, view(a.id()).status());
        assertTrue(attempts.findWork(PaymentAttempt.Status.APPROVING, PageRequest.of(0, 20)).contains(a.id()));
        payments.approve(authentication, a.id()); assertEquals(1, pg.approvals.get());
        assertEquals(PaymentAttempt.Status.APPROVED, new PaymentOrchestrator(stages, registry,pgSecrets).reconcileApproval(a.id()).status());
        assertEquals(PAID, state()); assertEquals(1, pg.approvals.get());
    }
    @Test void failedClaimRollsBackBeforeAnyPgCall() {
        var a = ready(); doThrow(new CannotAcquireLockException("injected claim failure")).when(attempts).flush();
        expect(503, () -> payments.approve(authentication, a.id())); reset(attempts);
        assertEquals(PENDING_PAYMENT, state()); assertEquals(PaymentAttempt.Status.READY, view(a.id()).status()); assertEquals(0, pg.approvals.get());
        payments.approve(authentication, a.id()); assertEquals(1, pg.approvals.get());
    }
    @Test void pgCancellationTimeoutIsNotFinalAndNeedsReconciliation() {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view(); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), claim.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED));
        pg.cancelMode = FakePgAdapter.Mode.SUCCESS_THEN_TIMEOUT;
        assertEquals(PaymentAttempt.Status.CANCEL_PENDING, payments.processCancellation(a.id()).status()); assertEquals(CANCEL_PENDING, state());
        payments.processCancellation(a.id()); assertEquals(1, pg.cancels.get());
        assertEquals(PaymentAttempt.Status.CANCELED, payments.reconcileCancellation(a.id()).status()); assertEquals(CANCELLED, state());
    }
    @Test void authorizationPrecedesReplayAndProviderConflict() {
        var a = ready();
        expect(404, () -> payments.approve(foreignAuthentication, a.id()));
        var restricted = auth(session(reservationId, Set.of("orders:read")));
        expect(403, () -> payments.createAttempt(restricted, orderId, pg.provider(), "attempt-key"));
        expect(409, () -> payments.createAttempt(authentication, orderId, "OTHER_PG", "attempt-key"));
        when(sessions.findSession(anyString())).thenThrow(new IllegalStateException("Redis outage"));
        expect(503, () -> payments.approve(authentication, a.id())); assertEquals(0, pg.approvals.get());
    }
    @Test void networkOrchestrationRefusesAnExistingTransaction() {
        var a = ready(); tx(() -> { assertThrows(IllegalStateException.class, () -> payments.approve(authentication, a.id())); return null; });
        assertEquals(0, pg.approvals.get()); assertEquals(PaymentAttempt.Status.READY, view(a.id()).status());
    }
    @Test void parallelApprovalCallsHaveOneWinnerAndOnePgCall() throws Exception {
        var a = ready(); pg.block(); var start = new CountDownLatch(1); var waiting = new CountDownLatch(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<PaymentTransactions.View>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { waiting.countDown(); await(start); return payments.approve(authentication, a.id()); }));
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); start.countDown(); assertTrue(pg.entered.await(5, TimeUnit.SECONDS)); }
            finally { pg.release.countDown(); start.countDown(); }
            for (var future : futures) assertEquals(a.id(), future.get(10, TimeUnit.SECONDS).id());
            assertEquals(1, pg.approvals.get()); assertEquals(PAID, state());
        }
    }
    @Test void parallelAttemptKeysCannotCreateTwoActiveApprovals() throws Exception {
        var start = new CountDownLatch(1); var waiting = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) { String key = "parallel-" + i; results.add(executor.submit(() -> {
                waiting.countDown(); await(start);
                try { payments.createAttempt(authentication, orderId, pg.provider(), key); return 201; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            })); }
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            var codes = new ArrayList<Integer>(); for (var future : results) codes.add(future.get(10, TimeUnit.SECONDS));
            Collections.sort(codes); assertEquals(List.of(201, 409), codes); assertEquals(0, pg.approvals.get());
        }
    }
    @Test void parallelCancelWorkersClaimOneExternalVoid() throws Exception {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view(); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), claim.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED)); pg.blockCancel();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<PaymentTransactions.View>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> payments.processCancellation(a.id())));
            try { assertTrue(pg.cancelEntered.await(5, TimeUnit.SECONDS)); } finally { pg.cancelRelease.countDown(); }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, pg.cancels.get()); assertEquals(CANCELLED, state());
        }
    }
    @Test void databaseSlotConstraintPreventsBypassOfApplicationGuards() {
        ready();
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> attempts.saveAndFlush(new PaymentAttempt(
                em.find(ServiceOrder.class, orderId), pg.provider(), PaymentAttempt.digest("direct-insert"), Instant.now()))));
        assertEquals(PENDING_PAYMENT, state()); assertEquals(0, pg.approvals.get());
    }
    @Test void confirmedVoidStillPreventsAnotherChargedAttemptAtDatabaseLevel() {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view(); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), claim.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED)); worker.drain(20);
        assertEquals(CANCELLED, state());
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> attempts.saveAndFlush(new PaymentAttempt(
                em.find(ServiceOrder.class, orderId), pg.provider(), PaymentAttempt.digest("recharge-bypass"), Instant.now()))));
        assertEquals(1, pg.cancels.get());
    }
    @Test void versionPredicateRejectsAStaleStateUpdate() throws Exception {
        var a = ready();
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThrows(OptimisticLockingFailureException.class, () -> tx(() -> {
                var stale = em.find(PaymentAttempt.class, a.id());
                try { assertTrue(executor.submit(() -> stages.claimApproval(authentication, a.id())).get(5, TimeUnit.SECONDS).claimed()); }
                catch (Exception e) { throw new IllegalStateException(e); }
                stale.requestCancel(Instant.now()); attempts.flush(); return null;
            }));
        }
        assertEquals(PaymentAttempt.Status.APPROVING, view(a.id()).status()); assertEquals(PAYMENT_PROCESSING, state());
    }
    @ParameterizedTest @ValueSource(strings = {"amount", "currency", "provider", "reference"})
    void unverifiedResultCannotChangeFinancialState(String field) {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view();
        var wrong = new PgAdapter.ApprovalResult(field.equals("reference") ? "wrong" : a.id(), field.equals("provider") ? "OTHER_PG" : a.provider(),
                PgAdapter.ApprovalOutcome.APPROVED, "tx", field.equals("amount") ? a.amount() + 1 : a.amount(), field.equals("currency") ? "USD" : a.currency(),a.merchant().externalId());
        expect(409, () -> stages.applyApproval(a.id(), claim.approvalClaim(), wrong));
        assertEquals(PaymentAttempt.Status.APPROVING, view(a.id()).status()); assertEquals(PAYMENT_PROCESSING, state());
    }
    @Test void unsupportedProviderCannotCreateAReadyAttempt() {
        expect(400, () -> payments.createAttempt(authentication, orderId, "NAVER_PAY", "not-configured"));
        assertEquals(0, attempts.count()); assertEquals(PENDING_PAYMENT, state()); assertEquals(0, pg.approvals.get());
    }
    @Test void creatingAttemptFailureRollsBackSlotAndKey() {
        doAnswer(call -> { em.persist(call.getArgument(0)); em.flush(); throw new DataIntegrityViolationException("injected insert failure"); })
                .when(attempts).saveAndFlush(any());
        expect(503, this::ready); reset(attempts);
        assertEquals(PaymentAttempt.Status.READY, ready().status()); assertEquals(PENDING_PAYMENT, state());
    }
    @Test void pgVoidSuccessWithLocalRollbackCanBeRecoveredWithoutAnotherVoid() {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view(); orders.cancel(authentication, orderId);
        stages.applyApproval(a.id(), claim.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED));
        pg.afterCancellation = command -> doAnswer(call -> { em.flush(); throw new DataAccessResourceFailureException("injected void result failure"); }).when(attempts).flush();
        expect(503, () -> payments.processCancellation(a.id())); reset(attempts);
        assertEquals(CANCEL_PENDING, state()); assertEquals(PaymentAttempt.Status.CANCEL_PENDING, view(a.id()).status());
        payments.processCancellation(a.id()); assertEquals(1, pg.cancels.get());
        assertEquals(PaymentAttempt.Status.CANCELED, new PaymentOrchestrator(stages, registry,pgSecrets).reconcileCancellation(a.id()).status());
        assertEquals(CANCELLED, state()); assertEquals(1, pg.cancels.get());
    }
    @Test void providerTransactionIdentityCannotBeAssignedToTwoOrders() {
        var a = ready(); var ca = stages.claimApproval(authentication, a.id()).view();
        stages.applyApproval(a.id(), ca.approvalClaim(), new PgAdapter.ApprovalResult(a.id(), a.provider(), PgAdapter.ApprovalOutcome.APPROVED, "shared-tx", a.amount(), a.currency(),a.merchant().externalId()));
        Long secondOrder = orders.create(authentication, "second-order", request()).order().id();
        var b = payments.createAttempt(authentication, secondOrder, pg.provider(), "attempt-b");
        var cb = stages.claimApproval(authentication, b.id()).view();
        expect(503, () -> stages.applyApproval(b.id(), cb.approvalClaim(), new PgAdapter.ApprovalResult(b.id(), b.provider(), PgAdapter.ApprovalOutcome.APPROVED, "shared-tx", b.amount(), b.currency(),b.merchant().externalId())));
        assertEquals(PAID, state()); assertEquals(PaymentAttempt.Status.APPROVING, view(b.id()).status());
        assertEquals(PaymentAttempt.Status.APPROVED, stages.applyApproval(b.id(), cb.approvalClaim(), result(b, PgAdapter.ApprovalOutcome.APPROVED)).status());
    }
    @Test void claimAndUnpaidCancelRaceHasOnlySafeOutcomes() throws Exception {
        var a = ready(); var start = new CountDownLatch(1); var waiting = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> { waiting.countDown(); await(start); return payments.approve(authentication, a.id()); });
            var cancellation = executor.submit(() -> { waiting.countDown(); await(start);
                try { return orders.cancel(authentication, orderId).accepted() ? 202 : 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            });
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            approval.get(10, TimeUnit.SECONDS); int code = cancellation.get(10, TimeUnit.SECONDS);
            assertTrue(Set.of(200, 202, 409).contains(code));
            var current = view(a.id());
            if (current.cancelRequested() && current.status() == PaymentAttempt.Status.CANCEL_PENDING) worker.drain(20);
            if (code == 409) assertEquals(PAID, state()); else assertEquals(CANCELLED, state());
            assertTrue(pg.approvals.get() <= 1); assertTrue(pg.cancels.get() <= 1);
        }
    }
    @Test void approvalResultCommitAndCancellationRacePreservesTheCommittedWinner() throws Exception {
        var a = ready(); var claim = stages.claimApproval(authentication, a.id()).view();
        var start = new CountDownLatch(1); var waiting = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> { waiting.countDown(); await(start);
                return stages.applyApproval(a.id(), claim.approvalClaim(), result(a, PgAdapter.ApprovalOutcome.APPROVED)); });
            var cancellation = executor.submit(() -> { waiting.countDown(); await(start);
                try { return orders.cancel(authentication, orderId).accepted() ? 202 : 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            });
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            approval.get(10, TimeUnit.SECONDS); int code = cancellation.get(10, TimeUnit.SECONDS);
            var current = view(a.id());
            if (code == 202) {
                assertTrue(current.cancelRequested()); assertEquals(CANCEL_PENDING, state());
                assertEquals(PaymentAttempt.Status.CANCEL_PENDING, current.status());
            } else {
                assertEquals(409, code); assertFalse(current.cancelRequested()); assertEquals(PAID, state());
                assertEquals(PaymentAttempt.Status.APPROVED, current.status());
            }
        }
    }
    @Test void newOrderCreationAndPaymentShareReservationLockWithoutCorruptingEitherSnapshot() throws Exception {
        var a = ready(); var start = new CountDownLatch(1); var waiting = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> { waiting.countDown(); await(start); return payments.approve(authentication, a.id()); });
            var creation = executor.submit(() -> { waiting.countDown(); await(start); return orders.create(authentication, "concurrent-order", request()).order(); });
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            assertEquals(PaymentAttempt.Status.APPROVED, approval.get(10, TimeUnit.SECONDS).status());
            var created = creation.get(10, TimeUnit.SECONDS);
            assertNotEquals(orderId, created.id()); assertEquals(2400, created.totalAmount());
            assertEquals(PENDING_PAYMENT, created.status()); assertEquals(PAID, state());
            assertEquals(2400, view(a.id()).amount()); assertEquals(1, pg.approvals.get());
        }
    }
    @Test void cancellationScopeIsRequiredEvenWhenApprovalIsInFlight() throws Exception {
        var a = ready(); stages.claimApproval(authentication, a.id());
        var restricted = session(reservationId, Set.of("orders:read"));
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + tokens.createAccessToken(restricted)))
                .andExpect(status().isForbidden());
        assertFalse(view(a.id()).cancelRequested()); assertEquals(PAYMENT_PROCESSING, state());
    }
    protected void await(CountDownLatch latch) { try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
}
