package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.HexFormat;
import java.util.UUID;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
public class PgWebhookService {
    public static final int MAX_BODY_BYTES = 65536;
    private final PgWebhookVerifiers verifiers;
    private final PgWebhookTransactions transactions;
    private final PgSecrets secrets;
    private final PaymentTransactions payments;
    public PgWebhookTransactions.Accepted receive(String provider, byte[] body, HttpHeaders headers) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Webhook verification requires no DB transaction");
        if (body == null || body.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (body.length > MAX_BODY_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        var verifier = verifiers.require(provider);
        PgWebhookVerifier.VerifiedEvent event;
        try {
            var context=secrets.context(verifier.merchantHint(body.clone(),HttpHeaders.readOnlyHttpHeaders(headers)));
            if(!provider.equals(context.provider()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            try(var secret=secrets.resolve(context)){event=verifier.verify(body.clone(),HttpHeaders.readOnlyHttpHeaders(headers),context,secret);}
            if(event==null||!context.id().equals(event.merchantId()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        catch (ResponseStatusException e) { throw e; }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook authentication failed"); }
        if (event == null || event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 200 || event.attemptId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verified webhook event");
        try { if (!UUID.fromString(event.attemptId()).toString().equals(event.attemptId())) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment reference"); }
        if(event.transactionId()!=null&&(event.transactionId().isBlank()||event.transactionId().length()>200))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        payments.validateWebhookReference(event.attemptId(),provider,event.merchantId(),event.transactionId());
        return transactions.store(provider,event.merchantId(), PaymentAttempt.digest(event.eventId()), event.attemptId(), digest(body),event.transactionId());
    }
    private String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
