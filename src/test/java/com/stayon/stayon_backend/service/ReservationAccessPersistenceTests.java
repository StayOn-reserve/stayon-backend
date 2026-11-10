package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.CustomerOtpProperties;
import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.entity.Site;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stayon.stayon_backend.service.ReservationAccessPolicy.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 only, with real JPA queries and a stubbed session store; no MySQL or Redis connection. */
@SpringBootTest
@CustomerJwtTestProperties
@ActiveProfiles("test")
@Transactional
class ReservationAccessPersistenceTests {
    @Autowired private EntityManager em;
    @Autowired private ReservationRepository reservations;
    @Autowired private ServiceOrderRepository orders;
    private Reservation reservation;
    private ReservationAccessPolicy policy;
    private Authentication authentication;

    @BeforeEach void setUp() {
        var site = new Site("access test site"); em.persist(site);
        reservation = reservation(site); em.persist(reservation);
        em.flush(); em.clear();
        var clock = Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC);
        var otp = new CustomerOtpProperties();
        otp.setHmacSecret("persistence-policy-test-hmac-key-at-least-32-bytes");
        var secrets = new CustomerAuthSecrets(otp, new CustomerJwtProperties());
        var store = mock(CustomerAuthStore.class);
        var scopes = Set.of("orders:read", "orders:create", "orders:cancel");
        when(store.findSession("session")).thenReturn(Optional.of(new CustomerAuthStore.Session("session",
                reservation.getId(), secrets.hmac("phone", "01012345678"), scopes,
                clock.instant().getEpochSecond() + 900)));
        authentication = new UsernamePasswordAuthenticationToken(new CustomerPrincipal(reservation.getId(), "session", scopes),
                null, List.of());
        policy = new ReservationAccessPolicy(reservations, orders, new CustomerSessionService(store, clock),
                secrets, new ReservationOrderWindow(clock, "Asia/Seoul", 0));
    }

    private Reservation reservation(Site site) {
        var result = new Reservation(); result.setPlatform(Platform.CAMFIT);
        result.setEventType(ReservationEventType.RESERVED); result.setSyncStatus("SUCCESS");
        result.setReservationId(UUID.randomUUID().toString()); result.setCustomerPhone("01012345678");
        result.setCheckInDate(LocalDate.of(2026, 11, 8)); result.setCheckOutDate(LocalDate.of(2026, 11, 10));
        result.setNights(2); result.setSite(site); return result;
    }

    @Test void fetchesSiteAndPreservesReservationsWithNoSite() {
        var linked = reservations.findForCustomerAccessById(reservation.getId()).orElseThrow();
        assertTrue(org.hibernate.Hibernate.isInitialized(linked.getSite()));
        assertEquals("access test site", linked.getSite().getName());
        var unlinked = reservation(null); em.persist(unlinked); em.flush(); em.clear();
        assertNull(reservations.findForCustomerAccessById(unlinked.getId()).orElseThrow().getSite());
        assertTrue(reservations.findForCustomerAccessById(Long.MAX_VALUE).isEmpty());
    }

    @Test void orderQueryRequiresBothOrderAndReservationIds() {
        var own = em.find(Reservation.class, reservation.getId());
        var order = orders.saveAndFlush(new ServiceOrder(own));
        var other = reservation(null); em.persist(other); em.flush(); em.clear();
        var found = orders.findByIdAndReservation_Id(order.getId(), own.getId()).orElseThrow();
        assertTrue(org.hibernate.Hibernate.isInitialized(found.getReservation()));
        assertEquals(own.getId(), found.getReservation().getId());
        assertTrue(orders.findByIdAndReservation_Id(order.getId(), other.getId()).isEmpty());
        assertTrue(orders.findByIdAndReservation_Id(Long.MAX_VALUE, own.getId()).isEmpty());
    }

    @Test void realQueriesAuthorizeOwnOrderAndHideForeignOrder() {
        var own = em.find(Reservation.class, reservation.getId());
        var ownOrder = orders.saveAndFlush(new ServiceOrder(own));
        var other = reservation(null); em.persist(other);
        var foreignOrder = orders.saveAndFlush(new ServiceOrder(other)); em.clear();
        assertFalse(policy.authorizeReservation(authentication, own.getId(), CREATE_ORDER).readOnly());
        assertEquals(ownOrder.getId(), policy.authorizeOrder(authentication, ownOrder.getId(), READ_ORDER).orderId());
        assertFalse(policy.authorizeOrder(authentication, ownOrder.getId(), CANCEL_ORDER).readOnly());
        var error = assertThrows(ResponseStatusException.class,
                () -> policy.authorizeOrder(authentication, foreignOrder.getId(), READ_ORDER));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test void changedPersistedContactIsRejectedOnNextAccess() {
        policy.authorizeReservation(authentication, reservation.getId(), READ_RESERVATION);
        em.find(Reservation.class, reservation.getId()).setCustomerPhone("01099998888"); em.flush(); em.clear();
        var error = assertThrows(ResponseStatusException.class,
                () -> policy.authorizeReservation(authentication, reservation.getId(), READ_RESERVATION));
        assertEquals(401, error.getStatusCode().value());
    }
}
