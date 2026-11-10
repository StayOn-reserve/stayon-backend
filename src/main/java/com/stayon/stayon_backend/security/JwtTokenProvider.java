package com.stayon.stayon_backend.security;

import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                jwtProperties.getSecret()
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    public String createAccessToken(Long userId, String role) {

        Date now = new Date();

        Date expiration = new Date(
                now.getTime() +
                        jwtProperties.getAccessTokenExpiration()
        );

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("token_use", "access")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }

    public String createRefreshToken(Long userId) {

        Date now = new Date();

        Date expiration = new Date(
                now.getTime() +
                        jwtProperties.getRefreshTokenExpiration()
        );

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("token_use", "refresh")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }
    public boolean validateAccessToken(String token) {
        try {
            Claims claims = parseClaims(token);
            Role.valueOf(claims.get("role", String.class));
            return "access".equals(claims.get("token_use", String.class))
                    && claims.getExpiration() != null && Long.parseLong(claims.getSubject()) > 0;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public boolean validateRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return "refresh".equals(claims.get("token_use", String.class))
                    && claims.getExpiration() != null && Long.parseLong(claims.getSubject()) > 0;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }
    public Long getUserId(String token) {

        return Long.valueOf(
                Jwts.parser()
                        .verifyWith(getSigningKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
                        .getSubject()
        );
    }
    public String getRole(String token) {

        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }
}
