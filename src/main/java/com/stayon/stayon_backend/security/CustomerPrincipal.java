package com.stayon.stayon_backend.security;

import java.util.Set;

/** Authenticated token identity only; reservation ownership and live session checks belong to access policy. */
public record CustomerPrincipal(Long reservationId, String sessionId, Set<String> scopes) {
    public CustomerPrincipal {
        scopes = Set.copyOf(scopes);
    }
}
