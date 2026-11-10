package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PaymentStateMachineTests {
    private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
    private ServiceOrder order(ServiceOrder.ServiceOrderStatus status) {
        var r = new Reservation(); r.setId(42L); var o = new ServiceOrder(r);
        ReflectionTestUtils.setField(o, "id", 7L); ReflectionTestUtils.setField(o, "totalAmount", 2400);
        ReflectionTestUtils.setField(o, "status", status); return o;
    }
    private PaymentAttempt attempt(PaymentAttempt.Status target) {
        var a = new PaymentAttempt(order(ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT), "TEST_PG", PaymentAttempt.digest("key"), now);
        if (target == PaymentAttempt.Status.READY) return a;
        a.claimApproval(now);
        switch (target) {
            case UNKNOWN -> a.unknown(a.getApprovalClaim(), now);
            case FAILED -> a.decline(a.getApprovalClaim(), now);
            case APPROVED -> a.approve(a.getApprovalClaim(), "tx", now);
            case CANCEL_PENDING, CANCELED -> {
                a.requestCancel(now); a.approve(a.getApprovalClaim(), "tx", now);
                if (target == PaymentAttempt.Status.CANCELED) { a.claimCancel(now); a.canceled(a.getCancelClaim(), now); }
            }
            default -> { }
        }
        return a;
    }
    @ParameterizedTest @EnumSource(ServiceOrder.ServiceOrderStatus.class)
    void approvalClaimHasOnlyOneOrderEntryState(ServiceOrder.ServiceOrderStatus state) {
        var o = order(state);
        if (state == ServiceOrder.ServiceOrderStatus.PENDING_PAYMENT) { o.beginPayment(); assertEquals(ServiceOrder.ServiceOrderStatus.PAYMENT_PROCESSING, o.getStatus()); }
        else { assertThrows(IllegalStateException.class, o::beginPayment); assertEquals(state, o.getStatus()); }
    }
    @ParameterizedTest @EnumSource(ServiceOrder.ServiceOrderStatus.class)
    void approvalResultCannotReverseTerminalOrder(ServiceOrder.ServiceOrderStatus state) {
        var o = order(state);
        if (state == ServiceOrder.ServiceOrderStatus.PAYMENT_PROCESSING || state == ServiceOrder.ServiceOrderStatus.CANCEL_PENDING) {
            o.paymentApproved(false);
            assertEquals(state == ServiceOrder.ServiceOrderStatus.CANCEL_PENDING ? state : ServiceOrder.ServiceOrderStatus.PAID, o.getStatus());
        } else { assertThrows(IllegalStateException.class, () -> o.paymentApproved(false)); assertEquals(state, o.getStatus()); }
    }
    @ParameterizedTest @EnumSource(PaymentAttempt.Status.class)
    void oneAttemptCanOnlyBeClaimedOnce(PaymentAttempt.Status state) {
        var a = attempt(state); assertEquals(state == PaymentAttempt.Status.READY, a.claimApproval(now));
        assertFalse(a.claimApproval(now));
    }
    @Test void cancellationIntentCannotBeDroppedByAResultFlag() {
        var success = order(ServiceOrder.ServiceOrderStatus.CANCEL_PENDING); success.paymentApproved(false);
        assertEquals(ServiceOrder.ServiceOrderStatus.CANCEL_PENDING, success.getStatus());
        var declined = order(ServiceOrder.ServiceOrderStatus.CANCEL_PENDING); declined.paymentDeclined(false);
        assertEquals(ServiceOrder.ServiceOrderStatus.CANCELLED, declined.getStatus());
    }
    @Test void timeoutReservesSlotAndDefinitiveDeclineReleasesIt() {
        var a = attempt(PaymentAttempt.Status.UNKNOWN); assertEquals(7L, a.getActiveOrderId());
        a.decline(a.getApprovalClaim(), now); assertNull(a.getActiveOrderId());
        assertThrows(IllegalStateException.class, () -> a.approve(a.getApprovalClaim(), "late-success", now));
    }
    @Test void confirmedVoidRetainsSuccessFenceAndReadyCancelDoesNot() {
        var charged = attempt(PaymentAttempt.Status.CANCELED);
        assertEquals(7L, charged.getApprovedOrderId()); assertEquals(7L, charged.getActiveOrderId());
        assertFalse(charged.canceled(charged.getCancelClaim(), now));
        var ready = attempt(PaymentAttempt.Status.READY); assertTrue(ready.requestCancel(now));
        assertEquals(PaymentAttempt.Status.CANCELED, ready.getStatus()); assertNull(ready.getActiveOrderId());
        assertFalse(ready.claimApproval(now));
    }
    @Test void staleClaimsAndDifferentTransactionIdsCannotChangeAnApprovedAttempt() {
        var a = attempt(PaymentAttempt.Status.APPROVED);
        assertThrows(IllegalStateException.class, () -> a.unknown("wrong", now));
        assertThrows(IllegalStateException.class, () -> a.approve(a.getApprovalClaim(), "different-tx", now));
        assertThrows(IllegalStateException.class, () -> a.decline(a.getApprovalClaim(), now));
        assertFalse(a.approve(a.getApprovalClaim(), "tx", now)); assertEquals(PaymentAttempt.Status.APPROVED, a.getStatus());
    }
}
