package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Set;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class CustomerSessionService {
    private final CustomerAuthStore store;
    private final Clock clock;

    public void requireActive(CustomerPrincipal principal, Set<String> requiredScopes) {
        requireActiveSession(principal, requiredScopes);
    }

    public CustomerAuthStore.Session requireActiveSession(CustomerPrincipal principal, Set<String> requiredScopes) {
        if (principal == null || principal.reservationId() == null || principal.reservationId() <= 0
                || principal.sessionId() == null || principal.sessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var session = storage(() -> store.findSession(principal.sessionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!principal.sessionId().equals(session.id()) || principal.reservationId() != session.reservationId()
                || session.expiresAt() <= clock.instant().getEpochSecond()
                || !session.scopes().containsAll(principal.scopes())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!principal.scopes().containsAll(requiredScopes)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return session;
    }

    public void logout(CustomerPrincipal principal) {
        storage(() -> { store.deleteSession(principal.sessionId()); return true; });
    }

    static <T> T storage(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            // No backend error details, keys, tokens or phone numbers are exposed.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "고객 인증 저장소를 사용할 수 없습니다.");
        }
    }
}
