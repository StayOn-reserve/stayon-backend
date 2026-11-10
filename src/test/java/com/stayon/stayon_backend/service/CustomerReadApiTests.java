package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import com.stayon.stayon_backend.security.CustomerJwtTokenProvider;
import com.stayon.stayon_backend.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stayon.stayon_backend.entity.AdditionalService.ServiceInputType.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security chain, JWT, policy and H2 repositories; session storage alone is stubbed. */
@SpringBootTest
@AutoConfigureMockMvc
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Transactional
class CustomerReadApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private ServiceOrderRepository orders;
    @Autowired private CustomerJwtTokenProvider tokens;
    @Autowired private JwtTokenProvider adminTokens;
    @Autowired private CustomerAuthSecrets secrets;
    @MockitoBean private CustomerAuthStore store;
    private final Map<String, CustomerAuthStore.Session> sessions = new HashMap<>();
    private Reservation reservation;
    private Reservation foreignReservation;
    private Site site;
    private AdditionalService product;
    private ServiceOrder ownOrder;
    private String authorization;

    @BeforeEach void setUp() {
        site = new Site("own site"); em.persist(site);
        var otherSite = new Site("other site"); em.persist(otherSite);
        reservation = reservation(site); em.persist(reservation);
        foreignReservation = reservation(otherSite); em.persist(foreignReservation);
        product = product(site, "Current catalog name", 9000, QUANTITY, true);
        product(site, "Inactive", 100, QUANTITY, false);
        product(site, "Time service", 100, DELIVERY_TIME, true);
        var foreignProduct = product(otherSite, "Foreign product", 100, QUANTITY, true);
        ownOrder = order(reservation, product);
        order(foreignReservation, foreignProduct);
        em.flush(); em.clear();
        when(store.findSession(anyString())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.getArgument(0))));
        authorization = bearer(Set.of("orders:read", "orders:create", "orders:cancel"));
    }

    private Reservation reservation(Site site) {
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var r = new Reservation(); r.setPlatform(Platform.CAMFIT); r.setReservationId(UUID.randomUUID().toString());
        r.setEventType(ReservationEventType.RESERVED); r.setSyncStatus("SUCCESS");
        r.setCustomerName("Private customer name"); r.setCustomerPhone("01012345678");
        r.setSite(site); r.setCheckInDate(today.minusDays(1)); r.setCheckOutDate(today.plusDays(1)); r.setNights(2);
        return r;
    }
    private AdditionalService product(Site site, String name, int price, AdditionalService.ServiceInputType type, boolean active) {
        var p = new AdditionalService(site, name, price, type, active,
                type == DELIVERY_TIME ? LocalTime.of(9, 0) : null,
                type == DELIVERY_TIME ? LocalTime.of(18, 0) : null, type == DELIVERY_TIME ? 5 : null);
        em.persist(p); return p;
    }
    private ServiceOrder order(Reservation r, AdditionalService p) {
        var result = new ServiceOrder(r);
        result.addItem(p, "Saved product name", 1000, 2, 2000);
        return orders.saveAndFlush(result);
    }
    private String bearer(Set<String> scopes) {
        var session = new CustomerAuthStore.Session(UUID.randomUUID().toString(), reservation.getId(),
                secrets.hmac("phone", "01012345678"), scopes, Instant.now().getEpochSecond() + 900);
        sessions.put(session.id(), session);
        return "Bearer " + tokens.createAccessToken(session);
    }

    @Test void reservationReturnsMinimalDtoFromJwtAndNeverUsesRequestedReservationId() throws Exception {
        mvc.perform(get("/customer/reservation").param("reservationId", foreignReservation.getId().toString())
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("RESERVED")).andExpect(jsonPath("$.orderable").value(true))
                .andExpect(jsonPath("$.checkInDate").value(reservation.getCheckInDate().toString()))
                .andExpect(jsonPath("$.checkOutDate").value(reservation.getCheckOutDate().toString()))
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.reservationId").doesNotExist()).andExpect(jsonPath("$.customerPhone").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist()).andExpect(jsonPath("$.site").doesNotExist());
    }

    @Test void catalogOnlyIncludesActiveQuantityProductsFromOwnSite() throws Exception {
        mvc.perform(get("/customer/reservation/additional-services").param("siteId", "-1")
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(product.getId().intValue()))
                .andExpect(jsonPath("$[0].name").value("Current catalog name"))
                .andExpect(jsonPath("$[0].price").value(9000)).andExpect(jsonPath("$[0].site").doesNotExist());
    }

    @Test void catalogOmitsInvalidLegacyProductInformation() throws Exception {
        em.createQuery("update AdditionalService s set s.price = -1 where s.id = :id").setParameter("id", product.getId()).executeUpdate();
        em.clear();
        mvc.perform(get("/customer/reservation/additional-services").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void catalogUsesCurrentDbPriceAndCreateScopeWithoutRequiringReadScope() throws Exception {
        em.find(AdditionalService.class, product.getId()).update("Updated catalog", 15000, QUANTITY, true, null, null, null);
        em.flush(); em.clear();
        String token = bearer(Set.of("orders:create"));
        mvc.perform(get("/customer/reservation/additional-services").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Updated catalog"))
                .andExpect(jsonPath("$[0].price").value(15000));
        mvc.perform(get("/customer/reservation").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/customer/orders").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test void orderListContainsOnlyOwnOrderAndStoredSnapshots() throws Exception {
        mvc.perform(get("/customer/orders").param("reservationId", foreignReservation.getId().toString())
                        .header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownOrder.getId().intValue()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(2000))
                .andExpect(jsonPath("$.content[0].items[0].serviceName").value("Saved product name"))
                .andExpect(jsonPath("$.content[0].items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.content[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.content[0].items[0].totalPrice").value(2000))
                .andExpect(jsonPath("$.content[0].reservation").doesNotExist())
                .andExpect(jsonPath("$.content[0].items[0].additionalService").doesNotExist());
    }

    @Test void catalogChangesNeverChangeHistoricalOrderResponse() throws Exception {
        var before = mvc.perform(get("/customer/orders").header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        em.find(AdditionalService.class, product.getId()).update("Changed name", 18000, QUANTITY, false, null, null, null);
        em.flush(); em.clear();
        mvc.perform(get("/customer/orders").header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(content().string(before));
    }

    @Test void historicalNullNameRemainsNullWithoutCatalogFallback() throws Exception {
        em.createNativeQuery("update service_order_item set service_name = null where service_order_id = :id")
                .setParameter("id", ownOrder.getId()).executeUpdate(); em.clear();
        mvc.perform(get("/customer/orders").header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].items[0].serviceName").value(nullValue()))
                .andExpect(content().string(containsString("\"serviceName\":null")));
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "checkedOut", "unlinked", "pendingSync", "beforeCheckIn"})
    void readOnlyReservationAllowsOwnOrdersButDeniesCatalog(String state) throws Exception {
        var r = em.find(Reservation.class, reservation.getId());
        switch (state) {
            case "cancelled" -> r.setEventType(ReservationEventType.CANCELLED);
            case "checkedOut" -> r.setCheckOutDate(LocalDate.now(ZoneId.of("Asia/Seoul")));
            case "unlinked" -> r.setSite(null);
            case "pendingSync" -> r.setSyncStatus("PENDING");
            case "beforeCheckIn" -> { r.setCheckInDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2));
                r.setCheckOutDate(r.getCheckInDate().plusDays(1)); }
            default -> throw new AssertionError(state);
        }
        em.flush(); em.clear();
        mvc.perform(get("/customer/reservation").header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(false));
        mvc.perform(get("/customer/orders").header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ownOrder.getId().intValue()));
        mvc.perform(get("/customer/reservation/additional-services").header("Authorization", authorization))
                .andExpect(status().isForbidden());
    }

    @Test void paginationIsReservationScopedAndOrdersAreNewestIdFirstWithoutTruncatingItems() throws Exception {
        var own = em.find(Reservation.class, reservation.getId());
        var p = em.find(AdditionalService.class, product.getId());
        var second = order(own, p);
        var third = new ServiceOrder(own);
        third.addItem(p, "Snapshot A", 100, 1, 100);
        var extra = product(em.find(Site.class, site.getId()), "Extra", 50, QUANTITY, true);
        third.addItem(extra, "Snapshot B", 50, 2, 100);
        orders.saveAndFlush(third); em.clear();
        mvc.perform(get("/customer/orders").param("page", "0").param("size", "2").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(third.getId().intValue()))
                .andExpect(jsonPath("$.content[0].items", hasSize(2)))
                .andExpect(jsonPath("$.content[0].items[0].serviceName").value("Snapshot A"))
                .andExpect(jsonPath("$.content[0].items[1].serviceName").value("Snapshot B"))
                .andExpect(jsonPath("$.content[1].id").value(second.getId().intValue()));
        mvc.perform(get("/customer/orders").param("page", "1").param("size", "2").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(ownOrder.getId().intValue()));
        mvc.perform(get("/customer/orders").param("page", "99").param("size", "2").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test void emptyOrderListHasStablePageMetadata() throws Exception {
        var empty = reservation(em.find(Site.class, site.getId())); em.persist(empty); em.flush();
        reservation = empty;
        var token = bearer(Set.of("orders:read")); em.clear();
        mvc.perform(get("/customer/orders").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0))).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @ParameterizedTest @ValueSource(strings = {"-1:20", "0:0", "0:101", "2147483647:100", "abc:20"})
    void rejectsInvalidPagination(String input) throws Exception {
        var values = input.split(":");
        mvc.perform(get("/customer/orders").param("page", values[0]).param("size", values[1])
                .header("Authorization", authorization)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"/customer/reservation", "/customer/reservation/additional-services", "/customer/orders"})
    void missingExpiredAndAdminTokensCannotAccessCustomerReads(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var expired = new CustomerAuthStore.Session(UUID.randomUUID().toString(), reservation.getId(), "hash", Set.of("orders:read"), 1);
        mvc.perform(get(path).header("Authorization", "Bearer " + tokens.createAccessToken(expired)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + adminTokens.createAccessToken(1L, "MANAGER")))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"/customer/reservation", "/customer/reservation/additional-services", "/customer/orders"})
    void missingOrExpiredRedisSessionIsUnauthorized(String path) throws Exception {
        var original = sessions.values().iterator().next(); sessions.clear();
        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isUnauthorized());
        sessions.put(original.id(), new CustomerAuthStore.Session(original.id(), original.reservationId(), original.phoneHash(), original.scopes(), 1));
        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"/customer/reservation", "/customer/reservation/additional-services", "/customer/orders"})
    void insufficientScopeIsForbidden(String path) throws Exception {
        String token = bearer(path.endsWith("additional-services") ? Set.of("orders:read") : Set.of("orders:cancel"));
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"/customer/reservation", "/customer/reservation/additional-services", "/customer/orders"})
    void redisFailureIsServiceUnavailable(String path) throws Exception {
        when(store.findSession(anyString())).thenThrow(new DataAccessResourceFailureException("private redis detail"));
        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest @ValueSource(strings = {"/customer/reservation", "/customer/reservation/additional-services", "/customer/orders"})
    void contactChangeIsUnauthorizedOnEveryRead(String path) throws Exception {
        em.find(Reservation.class, reservation.getId()).setCustomerPhone("01099998888"); em.flush(); em.clear();
        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isUnauthorized());
    }

    @Test void orderCreationRequiresExplicitBodyAndKeyAndDeleteRemainsUnavailable() throws Exception {
        mvc.perform(post("/customer/orders").header("Authorization", authorization)).andExpect(status().isBadRequest());
        mvc.perform(delete("/customer/orders/" + ownOrder.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
    }
}
