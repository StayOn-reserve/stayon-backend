package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.dto.customer.CustomerOrderCreateRequest;
import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.*;
import com.stayon.stayon_backend.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
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

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed H2 fixtures by default; the owned MySQL suite reuses these scenarios and independent transactions. */
@SpringBootTest
@AutoConfigureMockMvc
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Timeout(30)
class CustomerOrderCreationTests {
    @Autowired protected MockMvc mvc;
    @Autowired protected EntityManager em;
    @Autowired protected PlatformTransactionManager manager;
    @Autowired protected CustomerOrderService service;
    @Autowired protected ReservationRepository reservations;
    @Autowired protected AdditionalServiceRepository products;
    @Autowired protected CustomerJwtTokenProvider tokens;
    @Autowired protected JwtTokenProvider adminTokens;
    @Autowired protected CustomerAuthSecrets secrets;
    @MockitoBean protected CustomerAuthStore store;
    @MockitoSpyBean protected ServiceOrderRepository orders;
    private final Map<String, CustomerAuthStore.Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> scopes = Set.of("orders:read", "orders:create", "orders:cancel");
    protected Long reservationId, otherReservationId, siteId, otherSiteId, productId, secondId, foreignId;
    protected Authentication authentication;
    protected String bearer;

    protected <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(status -> work.get()); }

    @BeforeEach void setUp() {
        tx(() -> {
            var site = new Site("creation own site"); em.persist(site); siteId = site.getId();
            var other = new Site("creation other site"); em.persist(other); otherSiteId = other.getId();
            var reservation = reservation(site); em.persist(reservation); reservationId = reservation.getId();
            var foreign = reservation(other); em.persist(foreign); otherReservationId = foreign.getId();
            productId = product(site, "First snapshot", 1000, QUANTITY, true).getId();
            secondId = product(site, "Second snapshot", 3000, QUANTITY, true).getId();
            foreignId = product(other, "Foreign", 500, QUANTITY, true).getId();
            return null;
        });
        when(store.findSession(anyString())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.getArgument(0))));
        var session = session(reservationId, scopes);
        authentication = authenticated(session);
        bearer = "Bearer " + tokens.createAccessToken(session);
    }

    @AfterEach void cleanOwnCommittedH2Fixtures() {
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
        r.setCustomerPhone("01012345678"); r.setCheckInDate(today.minusDays(1)); r.setCheckOutDate(today.plusDays(1)); r.setNights(2);
        return r;
    }
    private AdditionalService product(Site site, String name, int price, AdditionalService.ServiceInputType type, boolean active) {
        var p = new AdditionalService(site, name, price, type, active, type == DELIVERY_TIME ? LocalTime.of(9, 0) : null,
                type == DELIVERY_TIME ? LocalTime.of(18, 0) : null, type == DELIVERY_TIME ? 5 : null);
        em.persist(p); return p;
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
    protected CustomerOrderCreateRequest request(int quantity) {
        return new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(productId, quantity)));
    }
    private String body(int quantity) { return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}"; }
    protected long count() { return tx(() -> em.createQuery("select count(o) from ServiceOrder o where o.reservation.id = :id", Long.class)
            .setParameter("id", reservationId).getSingleResult()); }
    protected void expectStatus(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @Test void createsSingleOrderAndReadApiReturnsStoredSnapshot() throws Exception {
        var result = service.create(authentication, "single", request(2));
        assertTrue(result.created()); assertEquals(ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT, result.order().status());
        assertEquals(2000, result.order().totalAmount()); assertEquals("First snapshot", result.order().items().getFirst().serviceName());
        assertEquals(1000, result.order().items().getFirst().unitPrice()); assertEquals(1, count());
        mvc.perform(get("/customer/orders").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(result.order().id().intValue()))
                .andExpect(jsonPath("$.content[0].items[0].totalPrice").value(2000));
    }

    @Test void httpCreateReturns201AndReplayReturns200WithoutPrivateMetadata() throws Exception {
        var response = mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "http-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body(2)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT")).andExpect(jsonPath("$.totalAmount").value(2000))
                .andExpect(jsonPath("$.idempotencyKeyHash").doesNotExist()).andExpect(jsonPath("$.requestHash").doesNotExist())
                .andExpect(jsonPath("$.reservation").doesNotExist()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "http-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body(2)))
                .andExpect(status().isOk()).andExpect(content().string(response));
        assertEquals(1, count());
    }

    @Test void multipleProductsUseServerPricesAndReorderedReplayIsEquivalent() {
        var a = new CustomerOrderCreateRequest.Item(productId, 2); var b = new CustomerOrderCreateRequest.Item(secondId, 3);
        var result = service.create(authentication, "multiple", new CustomerOrderCreateRequest(List.of(b, a)));
        assertEquals(11000, result.order().totalAmount()); assertEquals(2, result.order().items().size());
        var replay = service.create(authentication, "multiple", new CustomerOrderCreateRequest(List.of(a, b)));
        assertFalse(replay.created()); assertEquals(result.order(), replay.order()); assertEquals(1, count());
    }

    @Test void sameKeyDifferentRequestConflictsAndDifferentKeysAreIndependent() {
        var original = service.create(authentication, "same", request(1));
        expectStatus(409, () -> service.create(authentication, "same", request(2)));
        var different = service.create(authentication, "different", request(2));
        assertNotEquals(original.order().id(), different.order().id()); assertEquals(2, count());
    }

    @Test void sameKeyIsIndependentForAnotherAuthenticatedReservation() {
        var first = service.create(authentication, "shared-key", request(1));
        var other = service.create(authenticated(session(otherReservationId, scopes)), "shared-key",
                new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(foreignId, 1))));
        assertNotEquals(first.order().id(), other.order().id()); assertEquals(1, count());
    }

    @Test void newKeyUsesLatestPriceAndReplayKeepsOldSnapshotEvenAfterDeactivation() {
        var first = service.create(authentication, "old", request(2));
        tx(() -> { em.find(AdditionalService.class, productId).update("Updated", 1500, QUANTITY, true, null, null, null); return null; });
        var next = service.create(authentication, "new", request(2));
        assertEquals(3000, next.order().totalAmount()); assertEquals(2000, first.order().totalAmount());
        tx(() -> { em.find(AdditionalService.class, productId).changeActive(false); return null; });
        assertEquals(first.order(), service.create(authentication, "old", request(2)).order());
        expectStatus(409, () -> service.create(authentication, "disabled-new", request(2))); assertEquals(2, count());
    }

    @Test void replayRecoversExistingOrderAfterCancellationButCannotCreateANewOrder() {
        var original = service.create(authentication, "before-cancel", request(1));
        tx(() -> { em.find(Reservation.class, reservationId).setEventType(ReservationEventType.CANCELLED); return null; });
        assertEquals(original.order(), service.create(authentication, "before-cancel", request(1)).order());
        expectStatus(403, () -> service.create(authentication, "after-cancel", request(1)));
        assertEquals(1, count());
    }

    @ParameterizedTest @ValueSource(strings = {"zero", "negative", "nullQuantity", "nullId", "duplicate", "empty", "nullItems", "nullItem", "tooMany", "negativeId"})
    void invalidSelectionsNeverPersistAnything(String scenario) {
        var valid = request(1).items().getFirst();
        var invalid = switch (scenario) {
            case "zero" -> request(0);
            case "negative" -> request(-1);
            case "nullQuantity" -> new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(productId, null)));
            case "nullId" -> new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(null, 1)));
            case "negativeId" -> new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(-1L, 1)));
            case "duplicate" -> new CustomerOrderCreateRequest(List.of(valid, valid));
            case "empty" -> new CustomerOrderCreateRequest(List.of());
            case "nullItems" -> new CustomerOrderCreateRequest(null);
            case "nullItem" -> new CustomerOrderCreateRequest(Collections.singletonList(null));
            case "tooMany" -> new CustomerOrderCreateRequest(Collections.nCopies(101, valid));
            default -> throw new AssertionError(scenario);
        };
        expectStatus(400, () -> service.create(authentication, "invalid", invalid)); assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"", "has space", "한글", "long"})
    void rejectsInvalidIdempotencyKeys(String key) {
        String actual = key.equals("long") ? "a".repeat(129) : key;
        expectStatus(400, () -> service.create(authentication, actual, request(1))); assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"reservationId", "status", "totalAmount", "price", "name"})
    void rejectsClientControlledFields(String field) throws Exception {
        String json = field.equals("price") || field.equals("name")
                ? "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1,\"" + field + "\":0}]}"
                : "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}],\"" + field + "\":0}";
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "tampered")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"1.5", "\"2\"", "2147483648", "true"})
    void rejectsNonIntegerOrOutOfRangeJsonQuantity(String quantity) throws Exception {
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "invalid-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().isBadRequest()); assertEquals(0, count());
    }

    @Test void missingKeyIsBadRequest() throws Exception {
        mvc.perform(post("/customer/orders").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body(1)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "inactive", "time", "negativePrice", "overflow", "sumOverflow"})
    void invalidProductsOrAmountsAreRejected(String scenario) {
        var request = request(1); int expected = 409;
        switch (scenario) {
            case "foreign" -> { request = new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(foreignId, 1))); expected = 404; }
            case "inactive" -> tx(() -> { em.find(AdditionalService.class, productId).changeActive(false); return null; });
            case "time" -> tx(() -> { em.find(AdditionalService.class, productId).update("Time", 1000, DELIVERY_TIME, true,
                    LocalTime.of(9, 0), LocalTime.of(18, 0), 5); return null; });
            case "negativePrice" -> tx(() -> { em.createQuery("update AdditionalService s set s.price = -1 where s.id = :id").setParameter("id", productId).executeUpdate(); return null; });
            case "overflow" -> { request = request(Integer.MAX_VALUE); expected = 400; }
            case "sumOverflow" -> {
                tx(() -> { em.find(AdditionalService.class, productId).update("Max", Integer.MAX_VALUE, QUANTITY, true, null, null, null); return null; });
                request = new CustomerOrderCreateRequest(List.of(new CustomerOrderCreateRequest.Item(productId, 1), new CustomerOrderCreateRequest.Item(secondId, 1))); expected = 400;
            }
            default -> throw new AssertionError(scenario);
        }
        var input = request; expectStatus(expected, () -> service.create(authentication, "product-failure", input)); assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "checkout", "noSite", "sync", "phone"})
    void invalidReservationIsDeniedInsideWriteTransaction(String scenario) {
        tx(() -> { var r = em.find(Reservation.class, reservationId);
            switch (scenario) {
                case "cancelled" -> r.setEventType(ReservationEventType.CANCELLED);
                case "checkout" -> r.setCheckOutDate(LocalDate.now(ZoneId.of("Asia/Seoul")));
                case "noSite" -> r.setSite(null);
                case "sync" -> r.setSyncStatus("PENDING");
                case "phone" -> r.setCustomerPhone("01099998888");
                default -> throw new AssertionError(scenario);
            } return null; });
        expectStatus(scenario.equals("phone") ? 401 : 403, () -> service.create(authentication, "reservation-failure", request(1)));
        assertEquals(0, count());
    }

    @Test void jwtScopeAndSessionFailuresNeverCreateOrders() throws Exception {
        mvc.perform(post("/customer/orders").header("Idempotency-Key", "auth").contentType(MediaType.APPLICATION_JSON).content(body(1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/customer/orders").header("Authorization", "Bearer " + adminTokens.createAccessToken(1L, "MANAGER"))
                .header("Idempotency-Key", "auth").contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isUnauthorized());
        var readOnly = session(reservationId, Set.of("orders:read"));
        mvc.perform(post("/customer/orders").header("Authorization", "Bearer " + tokens.createAccessToken(readOnly))
                .header("Idempotency-Key", "auth").contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isForbidden());
        sessions.clear();
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "auth")
                .contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isUnauthorized());
        when(store.findSession(anyString())).thenThrow(new IllegalStateException("private redis error"));
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "auth")
                .contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isServiceUnavailable());
        assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"jwt", "session", "wrongReservation"})
    void invalidJwtOrSessionIsUnauthorized(String scenario) throws Exception {
        var original = sessions.values().iterator().next();
        if (scenario.equals("jwt")) bearer = "Bearer " + tokens.createAccessToken(new CustomerAuthStore.Session(
                original.id(), reservationId, original.phoneHash(), original.scopes(), 1));
        else sessions.put(original.id(), new CustomerAuthStore.Session(original.id(),
                scenario.equals("wrongReservation") ? otherReservationId : reservationId,
                original.phoneHash(), original.scopes(), scenario.equals("session") ? 1 : original.expiresAt()));
        mvc.perform(post("/customer/orders").header("Authorization", bearer).header("Idempotency-Key", "invalid-auth")
                .contentType(MediaType.APPLICATION_JSON).content(body(1))).andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @Test void realInsertAndItemsRollBackTogetherAndSameKeyCanBeRetried() {
        doAnswer(call -> { em.persist(call.getArgument(0)); em.flush();
            throw new DataIntegrityViolationException("forced failure after real inserts"); }).when(orders).saveAndFlush(any());
        expectStatus(503, () -> service.create(authentication, "rollback-key", request(1))); assertEquals(0, count());
        assertEquals(0L, tx(() -> em.createQuery("select count(i) from ServiceOrderItem i where i.serviceOrder.reservation.id = :id", Long.class)
                .setParameter("id", reservationId).getSingleResult()));
        reset(orders);
        assertTrue(service.create(authentication, "rollback-key", request(1)).created()); assertEquals(1, count());
    }

    @Test void lockFailureIsSafe503WithNoIdempotencyReservationLeftBehind() {
        doThrow(new CannotAcquireLockException("forced lock failure")).when(orders)
                .findByReservation_IdAndIdempotencyKeyHash(eq(reservationId), anyString());
        expectStatus(503, () -> service.create(authentication, "lock-key", request(1))); assertEquals(0, count());
        reset(orders);
        assertTrue(service.create(authentication, "lock-key", request(1)).created());
    }

    @Test void actualDatabaseUniqueConstraintRejectsDuplicateKeyButAllowsLegacyNullKeys() {
        var first = service.create(authentication, "constraint", request(1));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> {
            var saved = orders.findById(first.order().id()).orElseThrow();
            var duplicate = new ServiceOrder(saved.getReservation());
            duplicate.identifyCreation(saved.getIdempotencyKeyHash(), saved.getRequestHash());
            return orders.saveAndFlush(duplicate);
        }));
        assertEquals(1, count());
        tx(() -> { var r = em.find(Reservation.class, reservationId);
            orders.saveAndFlush(new ServiceOrder(r)); orders.saveAndFlush(new ServiceOrder(r)); return null; });
        assertEquals(3, count());
    }

    @Test void eightParallelSameKeyRequestsCommitExactlyOneOrder() throws Exception {
        var waiting = new CountDownLatch(8); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<CustomerOrderService.CreationResult>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                waiting.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.create(authentication, "parallel", request(2));
            }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); start.countDown();
            var values = new ArrayList<CustomerOrderService.CreationResult>();
            for (var future : results) values.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, values.stream().filter(CustomerOrderService.CreationResult::created).count());
            assertEquals(1, values.stream().map(value -> value.order().id()).distinct().count());
            assertEquals(1, count());
        } finally { start.countDown(); }
    }

    @Test void concurrentDifferentPayloadsUnderSameKeyHaveOneWinnerAndOneConflict() throws Exception {
        var waiting = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int quantity : List.of(1, 2)) futures.add(executor.submit(() -> {
                waiting.countDown(); await(start);
                try { return service.create(authentication, "competing-payload", request(quantity)).created() ? 201 : 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); start.countDown();
            var codes = new ArrayList<Integer>();
            for (var future : futures) codes.add(future.get(15, TimeUnit.SECONDS));
            Collections.sort(codes); assertEquals(List.of(201, 409), codes); assertEquals(1, count());
        } finally { start.countDown(); }
    }

    @Test void productWriterWaitsForOrderCommitAndCannotChangeItsSnapshot() throws Exception {
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1); var updating = new CountDownLatch(1);
        doAnswer(call -> {
            ServiceOrder order = call.getArgument(0); em.persist(order); em.flush();
            inserted.countDown(); await(release); return order;
        }).when(orders).saveAndFlush(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> service.create(authentication, "order-first", request(1)));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            var writer = executor.submit(() -> tx(() -> {
                em.find(AdditionalService.class, productId).update("Later price", 4000, QUANTITY, true, null, null, null);
                updating.countDown(); em.flush(); return null;
            }));
            assertTrue(updating.await(5, TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            var saved = creation.get(10, TimeUnit.SECONDS).order(); writer.get(10, TimeUnit.SECONDS);
            assertEquals(1000, saved.totalAmount());
            reset(orders);
            assertEquals(saved, service.create(authentication, "order-first", request(1)).order());
            assertEquals(4000, service.create(authentication, "after-price-change", request(1)).order().totalAmount());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"price", "inactive", "cancelled"})
    void concurrentCommittedChangeIsSeenAfterWaitingForRowLock(String change) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> tx(() -> {
                if (change.equals("cancelled")) reservations.findForCustomerOrderForUpdate(reservationId).orElseThrow()
                        .setEventType(ReservationEventType.CANCELLED);
                else {
                    var p = products.findForCustomerOrderForUpdate(productId, siteId).orElseThrow();
                    if (change.equals("price")) p.update("New price", 2500, QUANTITY, true, null, null, null);
                    else p.changeActive(false);
                }
                em.flush(); locked.countDown(); await(release); return null;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var creation = executor.submit(() -> { started.countDown(); return service.create(authentication, "race", request(1)); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> creation.get(200, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
            if (change.equals("price")) assertEquals(2500, creation.get(10, TimeUnit.SECONDS).order().totalAmount());
            else {
                var error = assertThrows(ExecutionException.class, () -> creation.get(10, TimeUnit.SECONDS));
                assertEquals(change.equals("inactive") ? 409 : 403, ((ResponseStatusException) error.getCause()).getStatusCode().value());
                assertEquals(0, count());
            }
        } finally { release.countDown(); }
    }

    protected void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
