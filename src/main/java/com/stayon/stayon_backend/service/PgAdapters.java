package com.stayon.stayon_backend.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import com.stayon.stayon_backend.entity.PaymentAttempt;
import java.util.*;

@Component
public class PgAdapters {
    private final Map<String, PgAdapter> adapters;
    public PgAdapters(ObjectProvider<PgAdapter> providers, PgRuntimeGuard guard) {
        var map = new HashMap<String, PgAdapter>();
        providers.orderedStream().forEach(adapter -> {
            guard.verify(adapter.provider(), adapter.testOnly());
            if (!PaymentAttempt.validProvider(adapter.provider()) || map.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalArgumentException("Invalid or duplicate PG provider");
        });
        adapters = Map.copyOf(map);
    }
    public PgAdapter require(String provider) {
        var result = adapters.get(provider);
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PG adapter is not configured");
        return result;
    }
}
