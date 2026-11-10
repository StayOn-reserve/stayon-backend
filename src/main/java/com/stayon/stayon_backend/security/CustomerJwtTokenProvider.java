package com.stayon.stayon_backend.security;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.Date;
import javax.crypto.SecretKey;

/** Customer-only JWT contract; issuance is called only after OTP validation by CustomerAuthService. */
@Component
public class CustomerJwtTokenProvider {
    private static final Set<String> ALLOWED_SCOPES = Set.of("orders:read", "orders:create", "orders:cancel");
    private final JwtParser parser;
    private final SecretKey signingKey;
    private final String issuer;
    private final String audience;

    public CustomerJwtTokenProvider(CustomerJwtProperties properties, JwtProperties adminProperties) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()
                || properties.getIssuer() == null || properties.getIssuer().isBlank()
                || properties.getAudience() == null || properties.getAudience().isBlank()) {
            throw new IllegalArgumentException("고객 JWT secret, issuer, audience 설정이 필요합니다.");
        }
        if (properties.getSecret().equals(adminProperties.getSecret())) {
            throw new IllegalArgumentException("고객 JWT 서명 키는 관리자 키와 달라야 합니다.");
        }
        var key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        signingKey = key;
        issuer = properties.getIssuer();
        audience = properties.getAudience();
        parser = Jwts.parser().verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .requireAudience(properties.getAudience())
                .require("token_use", "reservation_access")
                .build();
    }

    public String createAccessToken(Session session) {
        return Jwts.builder().issuer(issuer).audience().add(audience).and().subject(session.id())
                .claim("session_id", session.id()).claim("reservation_id", session.reservationId())
                .claim("scope", String.join(" ", session.scopes())).claim("token_use", "reservation_access")
                .issuedAt(new Date()).expiration(Date.from(java.time.Instant.ofEpochSecond(session.expiresAt())))
                .signWith(signingKey).compact();
    }

    public CustomerPrincipal parseAccessToken(String token) {
        var claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) {
            throw new JwtException("고객 토큰 만료 시각이 필요합니다.");
        }
        Object reservation = claims.get("reservation_id");
        if (!(reservation instanceof Long || reservation instanceof Integer)
                || ((Number) reservation).longValue() <= 0) {
            throw new JwtException("유효한 예약 식별자가 필요합니다.");
        }
        String sessionId = claims.get("session_id", String.class);
        if (sessionId == null || !UUID.fromString(sessionId).toString().equals(sessionId)
                || !sessionId.equals(claims.getSubject())) {
            throw new JwtException("유효한 고객 세션 식별자가 필요합니다.");
        }
        String scope = claims.get("scope", String.class);
        if (scope == null || scope.isBlank()) {
            throw new JwtException("고객 토큰 범위가 필요합니다.");
        }
        Set<String> scopes = Set.copyOf(Arrays.asList(scope.split(" ", -1)));
        if (!ALLOWED_SCOPES.containsAll(scopes)) {
            throw new JwtException("허용되지 않은 고객 토큰 범위입니다.");
        }
        return new CustomerPrincipal(((Number) reservation).longValue(), sessionId, scopes);
    }
}
