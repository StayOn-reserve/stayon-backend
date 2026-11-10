package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.ReservationEventType;
import com.stayon.stayon_backend.entity.ServiceOrder;
import com.stayon.stayon_backend.repository.AdditionalServiceRepository;
import com.stayon.stayon_backend.repository.ServiceOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static com.stayon.stayon_backend.service.ReservationAccessPolicy.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerReadServiceTests {
    private final ReservationAccessPolicy policy = mock(ReservationAccessPolicy.class);
    private final AdditionalServiceRepository products = mock(AdditionalServiceRepository.class);
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final Authentication authentication = mock(Authentication.class);
    private final CustomerReadService service = new CustomerReadService(policy, products, orders);

    @BeforeEach void setUp() {
        when(policy.authorizeCurrentReservation(eq(authentication), any())).thenAnswer(call ->
                new ReservationAccessPolicy.ReservationContext(new ReservationAccessPolicy.AccessContext(
                        42L, 20L, null, call.getArgument(1), false), ReservationEventType.RESERVED,
                        LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 10)));
    }

    private void unavailable(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ResponseStatusException.class, action);
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.toString().contains("private database detail"));
    }
    private void pageWithOrder() {
        var order = mock(ServiceOrder.class); when(order.getId()).thenReturn(7L);
        when(orders.findByReservation_Id(eq(42L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(order)));
    }

    @Test void reservationUsesAuthorizedSnapshotWithoutSecondRepositoryLookup() {
        var response = service.reservation(authentication);
        assertEquals(ReservationEventType.RESERVED, response.status()); assertTrue(response.orderable());
        verify(policy).authorizeCurrentReservation(authentication, READ_RESERVATION);
        verifyNoInteractions(products, orders);
    }

    @Test void catalogDatabaseFailureReturns503AfterCreateAuthorization() {
        when(products.findBySiteIdAndActiveTrueAndInputTypeOrderByIdAsc(eq(20L), any()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        unavailable(() -> service.additionalServices(authentication));
        verify(policy).authorizeCurrentReservation(authentication, CREATE_ORDER);
    }

    @Test void orderPageDatabaseFailureReturns503() {
        when(orders.findByReservation_Id(eq(42L), any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        unavailable(() -> service.orders(authentication, 0, 20));
        verify(policy).authorizeCurrentReservation(authentication, READ_ORDER);
    }

    @Test void orderItemDatabaseFailureReturns503WithoutPartialResponse() {
        pageWithOrder();
        when(orders.findCustomerOrdersWithItems(42L, List.of(7L)))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        unavailable(() -> service.orders(authentication, 0, 20));
    }

    @Test void disappearingOrderBetweenQueriesFailsClosed() {
        pageWithOrder(); when(orders.findCustomerOrdersWithItems(42L, List.of(7L))).thenReturn(List.of());
        unavailable(() -> service.orders(authentication, 0, 20));
    }

    @Test void failedPolicyNeverQueriesCatalogOrOrders() {
        when(policy.authorizeCurrentReservation(eq(authentication), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> service.additionalServices(authentication));
        assertThrows(ResponseStatusException.class, () -> service.orders(authentication, 0, 20));
        verifyNoInteractions(products, orders);
    }
}
