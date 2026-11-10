package com.stayon.stayon_backend.repository;

import java.util.Optional;
import java.util.Set;

/** Implementations must atomically enforce budgets, consumption and session creation. */
public interface CustomerAuthStore {
    record Challenge(String id, long reservationId, String phoneHash, String codeHash, String identity) { }
    record Session(String id, long reservationId, String phoneHash, Set<String> scopes, long expiresAt) {
        public Session { scopes = Set.copyOf(scopes); }
    }

    boolean allowIssue(String ipHash);
    boolean allowVerification(String ipHash);
    boolean begin(Challenge challenge);
    void sent(String challengeId);
    void invalidate(String challengeId);
    Optional<Challenge> findChallenge(String challengeId);
    boolean consume(Challenge challenge, String candidateHash, Session session);
    Optional<Session> findSession(String sessionId);
    void deleteSession(String sessionId);
}
