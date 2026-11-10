package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.*;
import com.stayon.stayon_backend.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.QUANTITY;
import static com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Independent committed transactions; reused unchanged by the owned MySQL cancellation suite. */
@SpringBootTest
@AutoConfigureMockMvc
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Timeout(30)
class CustomerOrderCancellationTests {
    @Autowired protected EntityManager em;
    @Autowired protected PlatformTransactionManager manager;
    @Autowired protected CustomerOrderService service;
    @Autowired protected ReservationRepository reservations;
    @Autowired private CustomerJwtTokenProvider tokens;
    @Autowired private JwtTokenProvider adminTokens;
    @Autowired private CustomerAuthSecrets secrets;
    @Autowired protected MockMvc mvc;
    @MockitoBean protected CustomerAuthStore store;
    @MockitoSpyBean protected ServiceOrderRepository orders;
    protected Long reservationId, otherReservationId, siteId, otherSiteId, productId, orderId, otherOrderId;
    protected Authentication authentication;
    protected String bearer;
    private CustomerAuthStore.Session ownSession;
    private final Map<String, CustomerAuthStore.Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> scopes = Set.of("orders:read", "orders:create", "orders:cancel");

    protected <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(status -> work.get()); }

    @BeforeEach void setUp() {
        Long foreignProduct = tx(() -> {
            var site = new Site("cancellation own site"); em.persist(site); siteId = site.getId();
            var other = new Site("cancellation other site"); em.persist(other); otherSiteId = other.getId();
            var reservation = reservation(site); em.persist(reservation); reservationId = reservation.getId();
            var foreign = reservation(other); em.persist(foreign); otherReservationId = foreign.getId();
            var p = new AdditionalService(site, "Original snapshot", 1200, QUANTITY, true, null, null, null);
            em.persist(p); productId = p.getId();
            var q = new AdditionalService(other, "Foreign product", 2000, QUANTITY, true, null, null, null);
            em.persist(q); return q.getId();
        });
        when(store.findSession(anyString())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.getArgument(0))));
        ownSession = session(reservationId, scopes); authentication = authenticated(ownSession);
        bearer = "Bearer " + tokens.createAccessToken(ownSession);
        orderId = service.create(authentication, "cancel-fixture", request()).order().id();
        otherOrderId = service.create(authenticated(session(otherReservationId, scopes)), "cancel-fixture",
                new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(foreignProduct, 1)))).order().id();
        clearInvocations(orders, store);
    }

    @AfterEach void cleanupOwnCommittedFixtures() {
        tx(() -> {
            var ids = List.of(reservationId, otherReservationId);
            em.createQuery("delete ServiceOrderItem i where i.serviceOrder.reservation.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete ServiceOrder o where o.reservation.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete AdditionalService s where s.site.id in :ids").setParameter("ids", List.of(siteId, otherSiteId)).executeUpdate();
            em.createQuery("delete Reservation r where r.id in :ids").setParameter("ids", ids).executeUpdate();
            em.createQuery("delete Site s where s.id in :ids").setParameter("ids", List.of(siteId, otherSiteId)).executeUpdate();
            return null;
        });
    }

    private Reservation reservation(Site site) {
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var r = new Reservation(); r.setPlatform(Platform.CAMFIT); r.setReservationId(UUID.randomUUID().toString());
        r.setEventType(ReservationEventType.RESERVED); r.setSyncStatus("SUCCESS"); r.setSite(site);
        r.setCheckInDate(today.minusDays(1)); r.setCheckOutDate(today.plusDays(1)); r.setNights(2);
        r.setCustomerPhone("01012345678"); return r;
    }
    private CustomerAuthStore.Session session(Long id, Set<String> scopes) {
        var s = new CustomerAuthStore.Session(UUID.randomUUID().toString(), id, secrets.hmac("phone", "01012345678"),
                scopes, Instant.now().getEpochSecond() + 900);
        sessions.put(s.id(), s); return s;
    }
    private Authentication authenticated(CustomerAuthStore.Session s) {
        return new UsernamePasswordAuthenticationToken(new CustomerPrincipal(s.reservationId(), s.id(), s.scopes()), null,
                List.of(new SimpleGrantedAuthority("CUSTOMER")));
    }
    protected CustomerOrderCreateRequest request() {
        return new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(productId, 2)));
    }
    protected ServiceOrder.ServiceOrderStatus state() { return tx(() -> em.find(ServiceOrder.class, orderId).getStatus()); }
    protected long count() { return tx(() -> em.createQuery("select count(o) from ServiceOrder o where o.reservation.id = :id", Long.class)
            .setParameter("id", reservationId).getSingleResult()); }
    protected void expectStatus(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
    private void changeReservation(String scenario) {
        tx(() -> { var r = em.find(Reservation.class, reservationId);
            switch (scenario) {
                case "cancelled" -> r.setEventType(ReservationEventType.CANCELLED);
                case "checkout" -> r.setCheckOutDate(LocalDate.now(ZoneId.of("Asia/Seoul")));
                case "noSite" -> r.setSite(null);
                case "sync" -> r.setSyncStatus("PENDING");
                default -> throw new AssertionError(scenario);
            } return null; });
    }

    @Test void pendingOrderIsCancelledAndHttpReplayReturnsSameStoredSnapshot() throws Exception {
        var first = mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(orderId.intValue())).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalAmount").value(2400))
                .andExpect(jsonPath("$.items[0].serviceName").value("Original snapshot"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1200)).andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.changed").doesNotExist()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(content().string(first));
        assertEquals(CANCELLED, state()); assertEquals(1, count()); verify(orders, times(1)).flush();
    }

    @Test void replayDoesNotIssueAnotherStateUpdateOrChangeCreationMetadata() {
        String hash = tx(() -> em.find(ServiceOrder.class, orderId).getRequestHash());
        assertTrue(service.cancel(authentication, orderId).changed()); clearInvocations(orders);
        assertFalse(service.cancel(authentication, orderId).changed()); verify(orders, never()).flush();
        assertEquals(hash, tx(() -> em.find(ServiceOrder.class, orderId).getRequestHash())); assertEquals(1, count());
        assertEquals(CANCELLED, service.create(authentication, "cancel-fixture", request()).order().status());
    }

    @ParameterizedTest @EnumSource(value = ServiceOrder.ServiceOrderStatus.class, names = {"PAID", "EXPIRED", "REFUNDED"})
    void paidOrOtherTerminalStateReturns409WithoutMutation(ServiceOrder.ServiceOrderStatus state) throws Exception {
        tx(() -> { em.createQuery("update ServiceOrder o set o.status = :state where o.id = :id")
                .setParameter("state", state).setParameter("id", orderId).executeUpdate(); return null; });
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isConflict());
        assertEquals(state, state()); verify(orders, never()).flush();
    }

    @Test void foreignOrderIs404EvenWithForgedReservationAndStatusInBody() throws Exception {
        mvc.perform(post("/customer/orders/" + otherOrderId + "/cancel").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reservationId\":" + reservationId + ",\"status\":\"PENDING_PAYMENT\"}"))
                .andExpect(status().isNotFound());
        verify(orders).findForCustomerCancellationForUpdate(otherOrderId, reservationId);
        verify(orders, never()).findById(anyLong());
        assertEquals(PENDING_PAYMENT, tx(() -> em.find(ServiceOrder.class, otherOrderId).getStatus()));
    }

    @Test void bodyCannotChooseReservationOrTargetState() throws Exception {
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reservationId\":" + otherReservationId + ",\"status\":\"PAID\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(CANCELLED, state());
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, Long.MAX_VALUE})
    void invalidOrMissingOrderIs404(long id) { expectStatus(404, () -> service.cancel(authentication, id)); }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "checkout", "noSite", "sync"})
    void pendingCancellationKeepsExistingReservationRestrictions(String scenario) throws Exception {
        changeReservation(scenario);
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isForbidden());
        assertEquals(PENDING_PAYMENT, state());
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "checkout", "noSite", "sync"})
    void cancelledReplayRemainsAllowedAfterReservationBecomesReadOnly(String scenario) throws Exception {
        service.cancel(authentication, orderId); changeReservation(scenario); clearInvocations(orders);
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(orders, never()).flush();
    }

    @Test void catalogChangesNeverAlterCancellationSnapshots() {
        tx(() -> { em.find(AdditionalService.class, productId).update("Changed product", 9999, QUANTITY, false, null, null, null); return null; });
        var result = service.cancel(authentication, orderId).order();
        assertEquals(2400, result.totalAmount()); assertEquals("Original snapshot", result.items().getFirst().serviceName());
        assertEquals(1200, result.items().getFirst().unitPrice());
    }

    @Test void historicalNullNameRemainsNullInCancellationResponse() throws Exception {
        tx(() -> { em.createNativeQuery("update service_order_item set service_name = null where service_order_id = :id")
                .setParameter("id", orderId).executeUpdate(); return null; });
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"serviceName\":null")));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "admin", "jwtExpired", "missingSession", "expiredSession", "wrongReservation", "wrongSession"})
    void invalidCustomerAuthenticationReturns401(String scenario) throws Exception {
        String token = bearer;
        switch (scenario) {
            case "missing" -> token = null;
            case "admin" -> token = "Bearer " + adminTokens.createAccessToken(1L, "MANAGER");
            case "jwtExpired" -> token = "Bearer " + tokens.createAccessToken(new CustomerAuthStore.Session(
                    ownSession.id(), reservationId, ownSession.phoneHash(), scopes, 1));
            case "missingSession" -> sessions.remove(ownSession.id());
            case "expiredSession" -> sessions.put(ownSession.id(), new CustomerAuthStore.Session(ownSession.id(), reservationId, ownSession.phoneHash(), scopes, 1));
            case "wrongReservation" -> sessions.put(ownSession.id(), new CustomerAuthStore.Session(ownSession.id(), otherReservationId, ownSession.phoneHash(), scopes, ownSession.expiresAt()));
            case "wrongSession" -> sessions.put(ownSession.id(), new CustomerAuthStore.Session(UUID.randomUUID().toString(), reservationId, ownSession.phoneHash(), scopes, ownSession.expiresAt()));
            default -> throw new AssertionError(scenario);
        }
        var request = post("/customer/orders/" + orderId + "/cancel"); if (token != null) request.header("Authorization", token);
        mvc.perform(request).andExpect(status().isUnauthorized()); assertEquals(PENDING_PAYMENT, state());
    }

    @Test void requiresCancelScopeAndAcceptsCancelOnlyScope() throws Exception {
        var read = session(reservationId, Set.of("orders:read"));
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + tokens.createAccessToken(read)))
                .andExpect(status().isForbidden()); assertEquals(PENDING_PAYMENT, state());
        var cancel = session(reservationId, Set.of("orders:cancel"));
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + tokens.createAccessToken(cancel)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings = {"changed", "missing", "masked"})
    void currentContactIsRequiredEvenForCancelledReplay(String contact) {
        service.cancel(authentication, orderId);
        tx(() -> { em.find(Reservation.class, reservationId).setCustomerPhone(switch (contact) {
            case "changed" -> "01099998888"; case "missing" -> null; default -> "010-****-5678";
        }); return null; });
        expectStatus(401, () -> service.cancel(authentication, orderId)); assertEquals(CANCELLED, state());
    }

    @ParameterizedTest @ValueSource(strings = {"filter", "afterLock"})
    void redisOutageReturns503WithoutMutation(String stage) throws Exception {
        if (stage.equals("filter")) when(store.findSession(ownSession.id())).thenThrow(new IllegalStateException("private Redis error"));
        else when(store.findSession(ownSession.id())).thenReturn(Optional.of(ownSession), Optional.of(ownSession))
                .thenThrow(new IllegalStateException("private Redis error"));
        mvc.perform(post("/customer/orders/" + orderId + "/cancel").header("Authorization", bearer)).andExpect(status().isServiceUnavailable());
        assertEquals(PENDING_PAYMENT, state());
    }

    @ParameterizedTest @ValueSource(strings = {"beforeUpdate", "afterUpdate"})
    void cancellationRollbackRestoresPendingStateAndAllowsRetry(String point) {
        doAnswer(call -> {
            if (point.equals("afterUpdate")) em.flush();
            throw new DataIntegrityViolationException("injected cancellation failure");
        }).when(orders).flush();
        expectStatus(503, () -> service.cancel(authentication, orderId)); assertEquals(PENDING_PAYMENT, state());
        assertEquals(1, count()); reset(orders);
        assertTrue(service.cancel(authentication, orderId).changed()); assertEquals(CANCELLED, state());
    }

    @Test void transientOrderLockFailureReturns503AndReleasesReservationLock() {
        doThrow(new CannotAcquireLockException("injected lock failure")).when(orders)
                .findForCustomerCancellationForUpdate(orderId, reservationId);
        expectStatus(503, () -> service.cancel(authentication, orderId)); assertEquals(PENDING_PAYMENT, state());
        reset(orders); assertTrue(service.cancel(authentication, orderId).changed());
    }

    @Test void eightParallelCancellationsHaveOneTransitionAndIdenticalResults() throws Exception {
        var waiting = new CountDownLatch(8); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<CustomerOrderService.CancellationResult>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                waiting.countDown(); await(start); return service.cancel(authentication, orderId);
            }));
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            var results = new ArrayList<CustomerOrderService.CancellationResult>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(CustomerOrderService.CancellationResult::changed).count());
            assertEquals(1, results.stream().map(CustomerOrderService.CancellationResult::order).distinct().count());
            assertEquals(CANCELLED, state()); assertEquals(1, count()); verify(orders, times(1)).flush();
        }
    }

    @Test void creationAndCancellationShareReservationLockWithoutCorruptingEitherOrder() throws Exception {
        var waiting = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> { waiting.countDown(); await(start); return service.cancel(authentication, orderId); });
            var create = executor.submit(() -> { waiting.countDown(); await(start); return service.create(authentication, "concurrent-create", request()); });
            try { assertTrue(waiting.await(5, TimeUnit.SECONDS)); } finally { start.countDown(); }
            assertTrue(cancel.get(10, TimeUnit.SECONDS).changed());
            var created = create.get(10, TimeUnit.SECONDS); assertTrue(created.created());
            assertNotEquals(orderId, created.order().id()); assertEquals(PENDING_PAYMENT, created.order().status());
            assertEquals(CANCELLED, state()); assertEquals(2, count());
        }
    }

    protected void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
