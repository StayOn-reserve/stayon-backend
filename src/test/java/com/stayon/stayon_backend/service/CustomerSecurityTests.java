package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.config.SecurityConfig;
import com.stayon.stayon_backend.security.CustomerJwtTokenProvider;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import com.stayon.stayon_backend.security.JwtAuthenticationFilter;
import com.stayon.stayon_backend.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CustomerSecurityTests.ProbeController.class)
@CustomerJwtTestProperties
@EnableConfigurationProperties({JwtProperties.class, CustomerJwtProperties.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        CustomerJwtTokenProvider.class, CustomerSecurityTests.ProbeController.class})
class CustomerSecurityTests {
    @MockitoBean private CustomerSessionService sessions;
    private static final String CUSTOMER = "/customer/_test/principal";
    private static final String MANAGEMENT = "/management/_test/principal";
    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider adminTokens;
    @Autowired private CustomerJwtTokenProvider customerTokens;
    @Autowired private CustomerJwtProperties customerProperties;
    @Autowired private JwtProperties adminProperties;

    @Test
    void adminAccessTokenRetainsLongPrincipalAndManagerAuthority() throws Exception {
        mvc.perform(get(MANAGEMENT).header("Authorization", "Bearer " + adminTokens.createAccessToken(7L, "MANAGER")))
                .andExpect(status().isOk()).andExpect(content().string("admin:7"));
    }

    @Test
    void customerAccessTokenCreatesCustomerPrincipalWithImmutableScopes() throws Exception {
        var token = customerToken("valid");
        mvc.perform(get(CUSTOMER).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("customer:42:" + SESSION_ID));
        var principal = customerTokens.parseAccessToken(token);
        assertEquals(Set.of("orders:read", "orders:create"), principal.scopes());
        assertThrows(UnsupportedOperationException.class, () -> principal.scopes().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/management/_test/principal", "/sites/1/additional-services", "/me"})
    void customerTokenIsRejectedOutsideCustomerChain(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + customerToken("valid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAccessAndRefreshTokensCannotAuthenticateCustomer() throws Exception {
        for (var token : new String[]{adminTokens.createAccessToken(7L, "MANAGER"), adminTokens.createRefreshToken(7L)}) {
            mvc.perform(get(CUSTOMER).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/management/_test/principal", "/sites/1/additional-services", "/me"})
    void refreshTokenCannotAuthenticateAnyOrdinaryApi(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + adminTokens.createRefreshToken(7L)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "missingIssuer", "audience", "missingAudience", "tokenUse", "missingTokenUse",
            "expired", "signature", "missingExpiration", "missingReservation", "negativeReservation", "zeroReservation",
            "stringReservation", "fractionalReservation", "missingSession", "invalidSession", "sessionType", "subjectMismatch",
            "missingSubject", "missingScope", "blankScope", "unknownScope", "scopeType", "roleOnly"})
    void invalidCustomerTokensAlwaysReturn401(String scenario) throws Exception {
        mvc.perform(get(CUSTOMER).header("Authorization", "Bearer " + customerToken(scenario)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/customer/_test/principal", "/customer", "/customer/orders/1",
            "/management/_test/principal", "/management"})
    void protectedPathsRejectMissingCredentials(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer ", "Bearer not-a-jwt"})
    void malformedCustomerAuthorizationReturns401(String header) throws Exception {
        mvc.perform(get(CUSTOMER).header("Authorization", header)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyCustomerHttpMethodRequiresAuthentication() throws Exception {
        mvc.perform(post("/customer/orders")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/customer/orders/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/customer/orders/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointRemainsPublicAndNoAuthenticationLeaksBetweenRequests() throws Exception {
        mvc.perform(get("/_test/public")).andExpect(status().isOk());
        mvc.perform(get(CUSTOMER).header("Authorization", "Bearer " + customerToken("valid"))).andExpect(status().isOk());
        mvc.perform(get(CUSTOMER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/_test/public")).andExpect(status().isOk());
    }

    @Test
    void wrongAdminRoleRemainsForbidden() throws Exception {
        mvc.perform(get(MANAGEMENT).header("Authorization", "Bearer " + adminTokens.createAccessToken(7L, "STAFF")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "signature", "missingUse", "wrongUse", "missingExpiration"})
    void invalidAdminTokensReturn401(String scenario) throws Exception {
        var builder = Jwts.builder().subject("7").claim("role", "MANAGER")
                .claim("token_use", "access").expiration(new Date(System.currentTimeMillis() + 60000));
        var key = adminProperties.getSecret();
        switch (scenario) {
            case "expired" -> builder.expiration(new Date(System.currentTimeMillis() - 60000));
            case "signature" -> key = customerProperties.getSecret();
            case "missingUse" -> builder.claim("token_use", null);
            case "wrongUse" -> builder.claim("token_use", "reservation_access");
            case "missingExpiration" -> builder.expiration(null);
            default -> throw new AssertionError(scenario);
        }
        var token = builder.signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get(MANAGEMENT).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void accessAndRefreshValidationAreMutuallyExclusive() {
        var access = adminTokens.createAccessToken(7L, "MANAGER");
        var refresh = adminTokens.createRefreshToken(7L);
        assertTrue(adminTokens.validateAccessToken(access));
        assertFalse(adminTokens.validateRefreshToken(access));
        assertTrue(adminTokens.validateRefreshToken(refresh));
        assertFalse(adminTokens.validateAccessToken(refresh));
    }

    @Test
    void missingCustomerConfigurationOrSharedKeyFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustomerJwtTokenProvider(new CustomerJwtProperties(), adminProperties));
        var properties = new CustomerJwtProperties();
        properties.setIssuer("test");
        properties.setAudience("test");
        properties.setSecret(adminProperties.getSecret());
        assertThrows(IllegalArgumentException.class, () -> new CustomerJwtTokenProvider(properties, adminProperties));
    }

    private String customerToken(String scenario) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("reservation_id", 42L);
        claims.put("session_id", SESSION_ID);
        claims.put("scope", "orders:read orders:create");
        claims.put("token_use", "reservation_access");
        var issuer = customerProperties.getIssuer();
        var audience = customerProperties.getAudience();
        var subject = SESSION_ID;
        var expiration = new Date(System.currentTimeMillis() + 60000);
        var secret = customerProperties.getSecret();
        switch (scenario) {
            case "valid" -> { }
            case "issuer" -> issuer = "other";
            case "missingIssuer" -> issuer = null;
            case "audience" -> audience = "other";
            case "missingAudience" -> audience = null;
            case "tokenUse" -> claims.put("token_use", "access");
            case "missingTokenUse" -> claims.remove("token_use");
            case "expired" -> expiration = new Date(System.currentTimeMillis() - 60000);
            case "signature" -> secret = adminProperties.getSecret();
            case "missingExpiration" -> expiration = null;
            case "missingReservation" -> claims.remove("reservation_id");
            case "negativeReservation" -> claims.put("reservation_id", -1);
            case "zeroReservation" -> claims.put("reservation_id", 0);
            case "stringReservation" -> claims.put("reservation_id", "42");
            case "fractionalReservation" -> claims.put("reservation_id", 42.5);
            case "missingSession" -> claims.remove("session_id");
            case "invalidSession" -> claims.put("session_id", "invalid");
            case "sessionType" -> claims.put("session_id", 123);
            case "subjectMismatch" -> subject = UUID.randomUUID().toString();
            case "missingSubject" -> subject = null;
            case "missingScope" -> claims.remove("scope");
            case "blankScope" -> claims.put("scope", " ");
            case "unknownScope" -> claims.put("scope", "orders:read admin:all");
            case "scopeType" -> claims.put("scope", List.of("orders:read"));
            case "roleOnly" -> {
                claims.remove("scope");
                claims.put("role", "MANAGER");
            }
            default -> throw new AssertionError(scenario);
        }
        var builder = Jwts.builder().claims(claims).issuer(issuer).subject(subject).expiration(expiration);
        if (audience != null) {
            builder.audience().add(audience).and();
        }
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    /** Test-only endpoints; no customer API is added to production. */
    @RestController
    static class ProbeController {
        @GetMapping(CUSTOMER)
        String customer(Authentication authentication) {
            if (!(authentication.getPrincipal() instanceof CustomerPrincipal principal)) {
                throw new AssertionError("CustomerPrincipal required");
            }
            return "customer:" + principal.reservationId() + ":" + principal.sessionId();
        }

        @GetMapping(MANAGEMENT)
        String management(Authentication authentication) {
            if (!(authentication.getPrincipal() instanceof Long userId)) {
                throw new AssertionError("Long admin principal required");
            }
            return "admin:" + userId;
        }

        @GetMapping("/_test/public")
        String publicEndpoint() {
            return "public";
        }
    }
}
