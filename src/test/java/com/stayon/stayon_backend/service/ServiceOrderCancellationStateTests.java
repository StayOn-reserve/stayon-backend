package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.entity.ServiceOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ServiceOrderCancellationStateTests {
    @ParameterizedTest @EnumSource(ServiceOrder.ServiceOrderStatus.class)
    void cancellationHasOneAllowedTransitionAndOneIdempotentTerminalState(ServiceOrder.ServiceOrderStatus status) {
        var reservation = new Reservation(); reservation.setId(42L);
        var order = new ServiceOrder(reservation); ReflectionTestUtils.setField(order, "status", status);
        if (status == ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT) {
            assertTrue(order.canCancelUnpaid()); assertTrue(order.cancelUnpaid());
            assertEquals(ServiceOrder.ServiceOrderStatus.CANCELLED, order.getStatus()); assertFalse(order.cancelUnpaid());
        } else if (status == ServiceOrder.ServiceOrderStatus.CANCELLED) {
            assertTrue(order.canCancelUnpaid()); assertFalse(order.cancelUnpaid());
        } else {
            assertFalse(order.canCancelUnpaid()); assertThrows(IllegalStateException.class, order::cancelUnpaid);
            assertEquals(status, order.getStatus());
        }
    }

    @Test void unknownStateNeverBecomesCancelled() {
        var reservation = new Reservation(); reservation.setId(42L);
        var order = new ServiceOrder(reservation); ReflectionTestUtils.setField(order, "status", null);
        assertFalse(order.canCancelUnpaid()); assertThrows(IllegalStateException.class, order::cancelUnpaid);
        assertNull(order.getStatus());
    }
}
