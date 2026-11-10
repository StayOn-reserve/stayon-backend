package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.entity.PaymentAttempt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Component
public class PgWebhookVerifiers {
    private final Map<String, PgWebhookVerifier> verifiers;
    public PgWebhookVerifiers(ObjectProvider<PgWebhookVerifier> candidates, PgAdapters adapters, PgRuntimeGuard guard) {
        var map = new HashMap<String, PgWebhookVerifier>();
        candidates.orderedStream().forEach(verifier -> {
            guard.verify(verifier.provider(), verifier.testOnly());
            if (!PaymentAttempt.validProvider(verifier.provider()) || map.putIfAbsent(verifier.provider(), verifier) != null)
                throw new IllegalArgumentException("Invalid or duplicate webhook provider");
            adapters.require(verifier.provider());
        });
        verifiers = Map.copyOf(map);
    }
    public PgWebhookVerifier require(String provider) {
        var verifier = verifiers.get(provider);
        if (verifier == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return verifier;
    }
}
