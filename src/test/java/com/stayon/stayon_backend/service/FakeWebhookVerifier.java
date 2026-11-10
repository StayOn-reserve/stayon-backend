package com.stayon.stayon_backend.service;

import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** TEST protocol only. This signature/body format is not a Naver/Kakao contract. */
final class FakeWebhookVerifier implements PgWebhookVerifier {
    @Override public String provider() { return "TEST_PG"; }
    @Override public boolean testOnly() { return true; }
    static String signature(byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            var parts=new String(body,StandardCharsets.UTF_8).split("\\|",-1);
            mac.init(new SecretKeySpec(FakePgSecretResolver.value(parts[3]).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public VerifiedEvent verify(byte[] body, HttpHeaders headers) {
        throw new UnsupportedOperationException("Merchant scope required");
    }
    @Override public String merchantHint(byte[] body,HttpHeaders headers){
        var parts=new String(body,StandardCharsets.UTF_8).split("\\|",-1);if(parts.length!=5)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return parts[3];
    }
    @Override public VerifiedEvent verify(byte[] body,HttpHeaders headers,PgMerchantContext merchant,PgSecret secret){
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        String supplied = headers.getFirst("X-Test-Signature");
        if (headers.getOrEmpty("X-Test-Signature").size() != 1 || supplied == null
                || !MessageDigest.isEqual(signature(body).getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid test signature");
        var parts = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 5||!parts[3].equals(merchant.id())||!new String(secret.copy()).equals(FakePgSecretResolver.value(merchant.id()))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new VerifiedEvent(parts[0], parts[1],parts[3],parts[4].isEmpty()?null:parts[4]); // claim is never trusted.
    }
}
