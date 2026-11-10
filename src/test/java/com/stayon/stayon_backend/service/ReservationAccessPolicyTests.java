package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.CustomerOtpProperties;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.entity.Site;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.stayon.stayon_backend.service.ReservationAccessPolicy.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationAccessPolicyTests {
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final CustomerAuthStore store = mock(CustomerAuthStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC);
    private final Set<String> scopes = Set.of("orders:read", "orders:create", "orders:cancel");
    private CustomerAuthSecrets secrets;
    private Session session;
    private Reservation reservation;
    private ServiceOrder order;
    private ReservationAccessPolicy policy;
    private Authentication authentication;

    @BeforeEach void setUp() {
        var otp = new CustomerOtpProperties();
        otp.setHmacSecret("policy-test-only-independent-hmac-key-at-least-32-bytes");
        secrets = new CustomerAuthSecrets(otp, new CustomerJwtProperties());
        reservation = new Reservation();
        reservation.setId(42L);
        reservation.setReservationId("not-an-access-credential");
        reservation.setCustomerName("same-name-is-not-an-access-credential");
        reservation.setCustomerPhone("+82 10-1234-5678");
        reservation.setEventType(ReservationEventType.RESERVED);
        reservation.setSyncStatus("SUCCESS");
        reservation.setCheckInDate(LocalDate.of(2026, 11, 8));
        reservation.setCheckOutDate(LocalDate.of(2026, 11, 10));
        var site = new Site("policy test site");
        ReflectionTestUtils.setField(site, "id", 20L);
        reservation.setSite(site);
        order = new ServiceOrder(reservation);
        ReflectionTestUtils.setField(order, "id", 7L);
        session = new Session("session", 42L, secrets.hmac("phone", "01012345678"), scopes,
                clock.instant().getEpochSecond() + 900);
        when(store.findSession("session")).thenAnswer(invocation -> Optional.of(session));
        when(reservations.findForCustomerAccessById(42L)).thenReturn(Optional.of(reservation));
        when(orders.findByIdAndReservation_Id(7L, 42L)).thenReturn(Optional.of(order));
        authentication = authenticated(new CustomerPrincipal(42L, "session", scopes));
        policy = new ReservationAccessPolicy(reservations, orders, new CustomerSessionService(store, clock),
                secrets, new ReservationOrderWindow(clock, "Asia/Seoul", 0));
    }

    private Authentication authenticated(Object principal) {
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("CUSTOMER")));
    }
    private ReservationAccessPolicy.AccessContext access(ReservationAccessPolicy.Operation operation) {
        return switch (operation) {
            case READ_RESERVATION, CREATE_ORDER -> policy.authorizeReservation(authentication, 42L, operation);
            case READ_ORDER, CANCEL_ORDER -> policy.authorizeOrder(authentication, 7L, operation);
        };
    }
    private void status(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @ParameterizedTest @EnumSource(ReservationAccessPolicy.Operation.class)
    void permitsOwnActiveReservationAndUnpaidOrder(ReservationAccessPolicy.Operation operation) {
        var result = access(operation);
        assertEquals(42L, result.reservationId()); assertEquals(20L, result.siteId());
        assertEquals(operation, result.operation()); assertFalse(result.readOnly());
        assertEquals(operation == READ_ORDER || operation == CANCEL_ORDER ? 7L : null, result.orderId());
        verify(store).findSession("session");
        verify(reservations).findForCustomerAccessById(42L);
    }

    @Test void rejectsOtherReservationBeforeQueryEvenIfExternalNumberAndNameCouldMatch() {
        status(403, () -> policy.authorizeReservation(authentication, 43L, READ_RESERVATION));
        verifyNoInteractions(reservations, orders);
    }

    @Test void rejectsUnexpectedReservationReturnedByRepository() {
        reservation.setId(43L);
        status(403, () -> access(READ_RESERVATION));
    }

    @ParameterizedTest @ValueSource(strings = {"reservation", "session", "expired", "missing", "sessionScopes"})
    void rejectsInvalidOrMissingRedisSession(String scenario) {
        session = switch (scenario) {
            case "reservation" -> new Session("session", 43L, session.phoneHash(), scopes, session.expiresAt());
            case "session" -> new Session("other-session", 42L, session.phoneHash(), scopes, session.expiresAt());
            case "expired" -> new Session("session", 42L, session.phoneHash(), scopes, clock.instant().getEpochSecond());
            case "sessionScopes" -> new Session("session", 42L, session.phoneHash(), Set.of("orders:read"), session.expiresAt());
            case "missing" -> session;
            default -> throw new AssertionError(scenario);
        };
        if (scenario.equals("missing")) when(store.findSession("session")).thenReturn(Optional.empty());
        status(401, () -> access(READ_RESERVATION));
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "010-****-5678", "unknown", "01099998888", "0101234567"})
    void rejectsMissingMaskedChangedOrInvalidPhone(String phone) {
        reservation.setCustomerPhone(phone);
        for (var operation : ReservationAccessPolicy.Operation.values()) status(401, () -> access(operation));
    }

    @Test void rejectsMissingPhoneHmacInSession() {
        session = new Session(session.id(), 42L, null, scopes, session.expiresAt());
        status(401, () -> access(READ_RESERVATION));
    }

    @Test void rechecksCurrentPhoneOnEveryInvocation() {
        access(READ_RESERVATION);
        reservation.setCustomerPhone("01099998888");
        status(401, () -> access(READ_RESERVATION));
        verify(reservations, times(2)).findForCustomerAccessById(42L);
        verify(store, times(2)).findSession("session");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "checkedOut", "checkoutToday", "noSite", "noSiteId", "invalidSiteId",
            "pendingSync", "failedSync", "unknownSync", "missingSync", "wrongCaseSync", "missingEvent",
            "missingCheckIn", "missingCheckOut", "invalidDates", "zeroNights", "beforeCheckIn"})
    void unsafeReservationIsReadOnlyAndDeniesCreateAndCancel(String scenario) {
        switch (scenario) {
            case "cancelled" -> reservation.setEventType(ReservationEventType.CANCELLED);
            case "checkedOut" -> { reservation.setCheckInDate(LocalDate.of(2026, 11, 1));
                reservation.setCheckOutDate(LocalDate.of(2026, 11, 7)); }
            case "checkoutToday" -> { reservation.setCheckInDate(LocalDate.of(2026, 11, 1));
                reservation.setCheckOutDate(LocalDate.of(2026, 11, 8)); }
            case "noSite" -> reservation.setSite(null);
            case "noSiteId" -> ReflectionTestUtils.setField(reservation.getSite(), "id", null);
            case "invalidSiteId" -> ReflectionTestUtils.setField(reservation.getSite(), "id", 0L);
            case "pendingSync" -> reservation.setSyncStatus("PENDING");
            case "failedSync" -> reservation.setSyncStatus("FAILED");
            case "unknownSync" -> reservation.setSyncStatus("UNRECOGNIZED_TEST_VALUE");
            case "missingSync" -> reservation.setSyncStatus(null);
            case "wrongCaseSync" -> reservation.setSyncStatus("success");
            case "missingEvent" -> reservation.setEventType(null);
            case "missingCheckIn" -> reservation.setCheckInDate(null);
            case "missingCheckOut" -> reservation.setCheckOutDate(null);
            case "invalidDates" -> reservation.setCheckOutDate(LocalDate.of(2026, 11, 7));
            case "zeroNights" -> reservation.setCheckOutDate(reservation.getCheckInDate());
            case "beforeCheckIn" -> reservation.setCheckInDate(LocalDate.of(2026, 11, 9));
            default -> throw new AssertionError(scenario);
        }
        assertTrue(access(READ_RESERVATION).readOnly());
        assertTrue(access(READ_ORDER).readOnly());
        status(403, () -> access(CREATE_ORDER)); status(403, () -> access(CANCEL_ORDER));
    }

    @Test void missingOrForeignOrderIsNotFoundWithoutUnscopedFallback() {
        when(orders.findByIdAndReservation_Id(8L, 42L)).thenReturn(Optional.empty());
        status(404, () -> policy.authorizeOrder(authentication, 8L, READ_ORDER));
        verify(orders).findByIdAndReservation_Id(8L, 42L);
        verify(orders, never()).findById(anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"reservation", "order", "missingReservation"})
    void verifiesReturnedOrderIdentityAndOwnership(String scenario) {
        if (scenario.equals("reservation")) {
            var foreign = new Reservation(); foreign.setId(43L);
            ReflectionTestUtils.setField(order, "reservation", foreign);
        } else if (scenario.equals("order")) ReflectionTestUtils.setField(order, "id", 8L);
        else ReflectionTestUtils.setField(order, "reservation", null);
        status(404, () -> access(READ_ORDER)); status(404, () -> access(CANCEL_ORDER));
    }

    @ParameterizedTest @EnumSource(value = ServiceOrder.ServiceOrderStatus.class, names = "PENDING_PAYMENT", mode = EnumSource.Mode.EXCLUDE)
    void terminalOrderIsReadOnlyAndOnlyCancelledReplayIsAllowed(ServiceOrder.ServiceOrderStatus status) {
        ReflectionTestUtils.setField(order, "status", status);
        assertTrue(access(READ_ORDER).readOnly());
        if (status == ServiceOrder.ServiceOrderStatus.CANCELLED || order.canRequestPaymentCancellation()) assertTrue(access(CANCEL_ORDER).readOnly());
        else status(409, () -> access(CANCEL_ORDER));
    }

    @Test void missingOrderStatusCannotBeCancelled() {
        ReflectionTestUtils.setField(order, "status", null);
        status(409, () -> access(CANCEL_ORDER));
    }

    @ParameterizedTest @EnumSource(ReservationAccessPolicy.Operation.class)
    void requiresOperationScopeInJwtEvenIfSessionHasAllScopes(ReservationAccessPolicy.Operation operation) {
        authentication = authenticated(new CustomerPrincipal(42L, "session", Set.of()));
        status(403, () -> access(operation)); verifyNoInteractions(reservations, orders);
    }

    @Test void readScopeDoesNotGrantCreateOrCancel() {
        authentication = authenticated(new CustomerPrincipal(42L, "session", Set.of("orders:read")));
        access(READ_RESERVATION); access(READ_ORDER);
        status(403, () -> access(CREATE_ORDER)); status(403, () -> access(CANCEL_ORDER));
    }

    @ParameterizedTest @ValueSource(strings = {"admin", "anonymous", "unauthenticated", "null", "missingReservationId", "missingSessionId"})
    void rejectsNonCustomerOrInvalidAuthentication(String scenario) {
        authentication = switch (scenario) {
            case "admin" -> new UsernamePasswordAuthenticationToken(1L, null,
                    List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
            case "anonymous" -> authenticated("anonymousUser");
            case "unauthenticated" -> new UsernamePasswordAuthenticationToken(new CustomerPrincipal(42L, "session", scopes), null);
            case "null" -> null;
            case "missingReservationId" -> authenticated(new CustomerPrincipal(null, "session", scopes));
            case "missingSessionId" -> authenticated(new CustomerPrincipal(42L, null, scopes));
            default -> throw new AssertionError(scenario);
        };
        int expected = scenario.equals("admin") || scenario.equals("anonymous") ? 403 : 401;
        status(expected, () -> access(READ_RESERVATION)); verifyNoInteractions(reservations, orders);
    }

    @Test void missingReservationIsNotFound() {
        when(reservations.findForCustomerAccessById(42L)).thenReturn(Optional.empty());
        status(404, () -> access(READ_RESERVATION));
    }

    @Test void reservationDbFailureFailsClosedWithoutExposingDetails() {
        when(reservations.findForCustomerAccessById(42L)).thenThrow(new DataAccessResourceFailureException("private database detail"));
        var error = assertThrows(ResponseStatusException.class, () -> access(CREATE_ORDER));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.toString().contains("private database detail"));
        verifyNoInteractions(orders);
    }

    @Test void orderDbFailureFailsClosed() {
        when(orders.findByIdAndReservation_Id(7L, 42L)).thenThrow(new DataAccessResourceFailureException("private database detail"));
        status(503, () -> access(READ_ORDER));
    }

    @Test void redisFailureFailsClosedBeforeDbLookup() {
        when(store.findSession("session")).thenThrow(new IllegalStateException("private redis detail"));
        status(503, () -> access(READ_RESERVATION)); verifyNoInteractions(reservations, orders);
    }

    @Test void refusesUsingWrongEntryPointForOperation() {
        assertThrows(IllegalArgumentException.class, () -> policy.authorizeReservation(authentication, 42L, READ_ORDER));
        assertThrows(IllegalArgumentException.class, () -> policy.authorizeOrder(authentication, 7L, CREATE_ORDER));
        assertThrows(IllegalArgumentException.class, () -> policy.authorizeOrder(authentication, 7L, null));
    }
}
