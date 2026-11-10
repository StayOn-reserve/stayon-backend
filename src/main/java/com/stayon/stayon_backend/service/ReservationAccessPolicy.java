package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.entity.ServiceOrder.ServiceOrderStatus;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Supplier;

/** Request-local authorization. Order creation locks the reservation inside its write transaction. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationAccessPolicy {
    private final ReservationRepository reservations;
    private final ServiceOrderRepository orders;
    private final CustomerSessionService sessions;
    private final CustomerAuthSecrets secrets;
    private final ReservationOrderWindow orderWindow;

    public enum Operation {
        READ_RESERVATION("orders:read"), READ_ORDER("orders:read"),
        CREATE_ORDER("orders:create"), CANCEL_ORDER("orders:cancel");

        private final String scope;
        Operation(String scope) { this.scope = scope; }
    }

    /** No entity, contact information or session secret is exposed. This is not a reusable grant. */
    public record AccessContext(Long reservationId, Long siteId, Long orderId, Operation operation, boolean readOnly) { }

    public record ReservationContext(AccessContext access, ReservationEventType status,
                                     LocalDate checkInDate, LocalDate checkOutDate) { }

    /** Current principal only; READ_ORDER here authorizes the reservation-scoped list, not an arbitrary order. */
    public ReservationContext authorizeCurrentReservation(Authentication authentication, Operation operation) {
        if (operation != Operation.READ_RESERVATION && operation != Operation.READ_ORDER && operation != Operation.CREATE_ORDER) {
            throw new IllegalArgumentException("예약 조회 또는 주문 목록/상품 조회 작업이 필요합니다.");
        }
        return reservationContext(customer(authentication), operation);
    }

    /** Locks and authenticates CREATE_ORDER scope. The caller must apply business policy before any new INSERT.
     * Existing idempotent results can still be recovered after checkout/cancellation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation lockOrderCreationScope(Authentication authentication) {
        return lockReservationScope(authentication, Operation.CREATE_ORDER);
    }

    /** Lock hierarchy: reservation first, then the owned order. Immutable snapshots need no product locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ServiceOrder lockOrderCancellation(Authentication authentication, Long orderId) {
        var reservation = lockReservationScope(authentication, Operation.CANCEL_ORDER);
        if (orderId == null || orderId <= 0) throw notFound();
        var order = database(() -> orders.findForCustomerCancellationForUpdate(orderId, reservation.getId()))
                .orElseThrow(ReservationAccessPolicy::notFound);
        verifyOrderIdentity(reservation.getId(), orderId, order);
        requireCancellationAllowed(reservation, order);
        return order;
    }

    private Reservation lockReservationScope(Authentication authentication, Operation operation) {
        var principal = customer(authentication);
        var session = sessions.requireActiveSession(principal, Set.of(operation.scope));
        var reservation = database(() -> reservations.findForCustomerOrderForUpdate(principal.reservationId()))
                .orElseThrow(ReservationAccessPolicy::notFound);
        verifyContact(principal, session, reservation);
        return reservation;
    }

    public AccessContext authorizeReservation(Authentication authentication, Long requestedReservationId, Operation operation) {
        if (operation != Operation.READ_RESERVATION && operation != Operation.CREATE_ORDER) {
            throw new IllegalArgumentException("예약 접근 작업이 필요합니다.");
        }
        var principal = customer(authentication);
        if (!Objects.equals(requestedReservationId, principal.reservationId())) throw denied();
        return reservationContext(principal, operation).access();
    }

    private ReservationContext reservationContext(CustomerPrincipal principal, Operation operation) {
        var reservation = currentReservation(principal, operation);
        boolean orderable = orderable(reservation);
        if (operation == Operation.CREATE_ORDER && !orderable) throw denied();
        return new ReservationContext(context(reservation, null, operation, !orderable), reservation.getEventType(),
                reservation.getCheckInDate(), reservation.getCheckOutDate());
    }

    public AccessContext authorizeOrder(Authentication authentication, Long orderId, Operation operation) {
        if (operation != Operation.READ_ORDER && operation != Operation.CANCEL_ORDER) {
            throw new IllegalArgumentException("주문 접근 작업이 필요합니다.");
        }
        var principal = customer(authentication);
        var reservation = currentReservation(principal, operation);
        if (orderId == null || orderId <= 0) throw notFound();
        var order = database(() -> orders.findByIdAndReservation_Id(orderId, principal.reservationId()))
                .orElseThrow(ReservationAccessPolicy::notFound);
        verifyOrderIdentity(principal.reservationId(), orderId, order);
        boolean orderable = orderable(reservation);
        if (operation == Operation.CANCEL_ORDER) requireCancellationAllowed(reservation, order);
        return context(reservation, orderId, operation, !orderable || order.getStatus() != ServiceOrderStatus.PENDING_PAYMENT);
    }

    private void verifyOrderIdentity(Long reservationId, Long orderId, ServiceOrder order) {
        if (!orderId.equals(order.getId()) || order.getReservation() == null
                || !reservationId.equals(order.getReservation().getId())) throw notFound();
    }

    private void requireCancellationAllowed(Reservation reservation, ServiceOrder order) {
        if (!order.canCancelUnpaid() && !order.canRequestPaymentCancellation())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 상태에서는 취소할 수 없습니다.");
        if (order.getStatus() == ServiceOrderStatus.PENDING_PAYMENT && !orderable(reservation)) throw denied();
    }

    private CustomerPrincipal customer(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!(authentication.getPrincipal() instanceof CustomerPrincipal principal)) throw denied();
        if (principal.reservationId() == null || principal.reservationId() <= 0
                || principal.sessionId() == null || principal.sessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal;
    }

    private Reservation currentReservation(CustomerPrincipal principal, Operation operation) {
        var session = sessions.requireActiveSession(principal, Set.of(operation.scope));
        var reservation = database(() -> reservations.findForCustomerAccessById(principal.reservationId()))
                .orElseThrow(ReservationAccessPolicy::notFound);
        verifyContact(principal, session, reservation);
        return reservation;
    }

    private void verifyContact(CustomerPrincipal principal, Session session, Reservation reservation) {
        if (!principal.reservationId().equals(reservation.getId())) throw denied();
        String phone = secrets.normalizePhone(reservation.getCustomerPhone());
        if (phone == null || !secrets.matches(session.phoneHash(), secrets.hmac("phone", phone))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private boolean orderable(Reservation reservation) {
        return reservation.getEventType() == ReservationEventType.RESERVED
                && "SUCCESS".equals(reservation.getSyncStatus())
                && reservation.getSite() != null && reservation.getSite().getId() != null
                && reservation.getSite().getId() > 0
                && orderWindow.allows(reservation.getCheckInDate(), reservation.getCheckOutDate());
    }

    private AccessContext context(Reservation reservation, Long orderId, Operation operation, boolean readOnly) {
        return new AccessContext(reservation.getId(), reservation.getSite() == null ? null : reservation.getSite().getId(),
                orderId, operation, readOnly);
    }

    private static <T> T database(Supplier<T> query) {
        try { return query.get(); }
        catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "예약 접근 정보를 확인할 수 없습니다.");
        }
    }

    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
