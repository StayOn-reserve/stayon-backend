package com.stayon.stayon_backend.dto.customer;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import com.stayon.stayon_backend.service.PaymentTransactions;

/** Explicit public allowlist: no PG transaction ID, claims, authentication proof or recovery internals. */
public record CustomerPaymentResponse(String id, Long orderId, String provider, long amount, String currency,
                                      PaymentAttempt.Status status, boolean cancelRequested) {
    public static CustomerPaymentResponse from(PaymentTransactions.View v) {
        return new CustomerPaymentResponse(v.id(), v.orderId(), v.provider(), v.amount(), v.currency(), v.status(), v.cancelRequested());
    }
    public static CustomerPaymentResponse from(PaymentAttempt a) {
        return new CustomerPaymentResponse(a.getId(), a.getServiceOrder().getId(), a.getProvider(), a.getAmount(), a.getCurrency(),
                a.getStatus(), a.getCancelRequestedAt() != null);
    }
}
