package com.stayon.stayon_backend.service;

import org.springframework.http.HttpHeaders;

/** Provider-owned signature/authentication, replay-window and canonical reference extraction contract.
 * Implementations must authenticate the original bytes before returning an event. No real provider format is assumed. */
public interface PgWebhookVerifier {
    String provider();
    default boolean testOnly() { return false; }
    VerifiedEvent verify(byte[] originalBody, HttpHeaders headers);
    default String merchantHint(byte[] body,HttpHeaders headers){throw new UnsupportedOperationException("Merchant identification is required");}
    default VerifiedEvent verify(byte[] body,HttpHeaders headers,PgMerchantContext merchant,PgSecret secret){throw new UnsupportedOperationException("Merchant-scoped verification is required");}
    record VerifiedEvent(String eventId, String attemptId,String merchantId,String transactionId) {
        @Override public String toString() { return "VerifiedEvent[REDACTED]"; }
    }
}
