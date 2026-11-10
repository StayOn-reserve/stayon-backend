package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.*;
import com.stayon.stayon_backend.controller.CustomerAuthController;
import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CustomerAuthController.class, CustomerSecurityTests.ProbeController.class})
@CustomerJwtTestProperties
@EnableConfigurationProperties({JwtProperties.class, CustomerJwtProperties.class, CustomerOtpProperties.class})
@Import({SecurityConfig.class, CustomerAuthConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        CustomerJwtTokenProvider.class, CustomerSessionService.class, CustomerAuthService.class,
        CustomerAuthSecrets.class, CustomerAuthController.class, CustomerSecurityTests.ProbeController.class})
class CustomerAuthApiTests {
    private static final String BASE = "/customer/auth";
    @Autowired private MockMvc mvc;
    @Autowired private CustomerJwtTokenProvider tokens;
    @Autowired private JwtTokenProvider adminTokens;
    @MockitoBean private CustomerAuthStore store;
    @MockitoBean private ReservationRepository reservations;
    @MockitoBean private SmsSender sms;
    private final Map<String, CustomerAuthStore.Session> sessions = new ConcurrentHashMap<>();
    private CustomerAuthStore.Challenge challenge;
    private String sentOtp;

    @BeforeEach
    void setUp() {
        var reservation = new Reservation(); reservation.setId(42L); reservation.setReservationId("booking-1");
        reservation.setCustomerPhone("010-1234-5678");
        when(reservations.findByPlatformAndReservationId(Platform.CAMFIT, "booking-1")).thenReturn(Optional.of(reservation));
        when(reservations.findById(42L)).thenReturn(Optional.of(reservation));
        when(store.allowIssue(anyString())).thenReturn(true);
        when(store.allowVerification(anyString())).thenReturn(true);
        when(store.begin(any())).thenAnswer(call -> { challenge = call.getArgument(0); return true; });
        doAnswer(call -> { sentOtp = call.getArgument(1); return null; }).when(sms).sendOtp(anyString(), anyString(), anyString());
        when(store.findChallenge(anyString())).thenAnswer(call -> challenge != null && challenge.id().equals(call.getArgument(0))
                ? Optional.of(challenge) : Optional.empty());
        when(store.consume(any(), anyString(), any())).thenAnswer(call -> {
            CustomerAuthStore.Session session = call.getArgument(2);
            sessions.put(session.id(), session); challenge = null; return true;
        });
        when(store.findSession(anyString())).thenAnswer(call -> Optional.ofNullable(sessions.get(call.getArgument(0))));
        doAnswer(call -> { sessions.remove(call.getArgument(0)); return null; }).when(store).deleteSession(anyString());
    }

    @Test
    void publicOtpEndpointsCreateTokenAndProtectedLogoutRevokesSession() throws Exception {
        mvc.perform(post(BASE + "/otp/challenges").contentType(MediaType.APPLICATION_JSON).content(body("booking-1", "01012345678")))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("expiresIn").value(180)).andExpect(jsonPath("challengeId").value(challenge.id()));
        var response = mvc.perform(post(BASE + "/otp/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge.id() + "\",\"otp\":\"" + sentOtp + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("tokenType").value("Bearer")).andExpect(jsonPath("expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        // Only extract the fixture token; never print the response or token.
        String token = response.split("\"accessToken\":\"")[1].split("\"")[0];
        mvc.perform(get("/customer/_test/principal").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post(BASE + "/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/customer/_test/principal").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertTrue(sessions.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mismatch", "senderFailure"})
    void challengeResponsesNeverRevealEligibilityOrSenderFailure(String scenario) throws Exception {
        if (scenario.equals("senderFailure")) doThrow(new IllegalStateException("test failure")).when(sms).sendOtp(anyString(), anyString(), anyString());
        String response = mvc.perform(post(BASE + "/otp/challenges").contentType(MediaType.APPLICATION_JSON)
                        .content(body(scenario.equals("missing") ? "unknown" : "booking-1",
                                scenario.equals("mismatch") ? "01099999999" : "01012345678")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("expiresIn").value(180))
                .andExpect(jsonPath("challengeId").isString()).andExpect(jsonPath("phone").doesNotExist())
                .andExpect(jsonPath("reservationId").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("01012345678"));
        assertFalse(response.contains("booking-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/customer/auth/otp/challenges/extra", "/customer/auth/otp/verifications/extra", "/customer/auth/logout", "/customer/orders"})
    void onlyExactOtpPostEndpointsArePublic(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
    }

    @Test void otpGetEndpointsAndMissingCredentialsAreRejected() throws Exception {
        mvc.perform(get(BASE + "/otp/challenges")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/otp/verifications")).andExpect(status().isUnauthorized());
    }

    @Test void unknownChallengeReturnsGeneric401() throws Exception {
        mvc.perform(post(BASE + "/otp/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"otp\":\"12345678\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "wrongReservation", "wrongSession", "wrongScope"})
    void invalidRedisSessionsReturn401(String scenario) throws Exception {
        var original = session();
        switch (scenario) {
            case "missing" -> { }
            case "expired" -> sessions.put(original.id(), new CustomerAuthStore.Session(original.id(), 42L, "hash", original.scopes(), 1));
            case "wrongReservation" -> sessions.put(original.id(), new CustomerAuthStore.Session(original.id(), 43L, "hash", original.scopes(), original.expiresAt()));
            case "wrongSession" -> sessions.put(original.id(), new CustomerAuthStore.Session(UUID.randomUUID().toString(), 42L, "hash", original.scopes(), original.expiresAt()));
            case "wrongScope" -> sessions.put(original.id(), new CustomerAuthStore.Session(original.id(), 42L, "hash", Set.of("orders:cancel"), original.expiresAt()));
            default -> throw new AssertionError(scenario);
        }
        mvc.perform(get("/customer/_test/principal").header("Authorization", "Bearer " + tokens.createAccessToken(original)))
                .andExpect(status().isUnauthorized());
    }

    @Test void redisOutageReturns503ForSessionLookupIssueVerificationAndLogout() throws Exception {
        var session = session(); sessions.put(session.id(), session);
        var token = tokens.createAccessToken(session);
        when(store.findSession(anyString())).thenThrow(new DataAccessResourceFailureException("test backend unavailable"));
        mvc.perform(get("/customer/_test/principal").header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        when(store.allowIssue(anyString())).thenThrow(new DataAccessResourceFailureException("test backend unavailable"));
        mvc.perform(post(BASE + "/otp/challenges").contentType(MediaType.APPLICATION_JSON).content(body("booking-1", "01012345678")))
                .andExpect(status().isServiceUnavailable());
        when(store.allowVerification(anyString())).thenThrow(new DataAccessResourceFailureException("test backend unavailable"));
        mvc.perform(post(BASE + "/otp/verifications").contentType(MediaType.APPLICATION_JSON).content("{\"challengeId\":\"x\",\"otp\":\"12345678\"}"))
                .andExpect(status().isServiceUnavailable());
        doReturn(Optional.of(session)).when(store).findSession(session.id());
        doThrow(new DataAccessResourceFailureException("test backend unavailable")).when(store).deleteSession(session.id());
        mvc.perform(post(BASE + "/logout").header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
    }

    @Test void adminAndRefreshTokensAreStillRejectedByCustomerSessionChain() throws Exception {
        for (String token : new String[]{adminTokens.createAccessToken(7L, "MANAGER"), adminTokens.createRefreshToken(7L)}) {
            mvc.perform(post(BASE + "/logout").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        verify(store, never()).findSession(anyString());
    }

    @Test void expiredJwtIsRejectedBeforeSessionRead() throws Exception {
        var session = session();
        var expired = new CustomerAuthStore.Session(session.id(), 42L, "hash", session.scopes(), 1);
        mvc.perform(get("/customer/_test/principal").header("Authorization", "Bearer " + tokens.createAccessToken(expired)))
                .andExpect(status().isUnauthorized());
        verify(store, never()).findSession(anyString());
    }

    private CustomerAuthStore.Session session() {
        return new CustomerAuthStore.Session(UUID.randomUUID().toString(), 42L, "phone-hash", Set.of("orders:read"), Instant.now().getEpochSecond() + 900);
    }
    private String body(String reservation, String phone) {
        return "{\"platform\":\"CAMFIT\",\"reservationNumber\":\"" + reservation + "\",\"phoneNumber\":\"" + phone + "\"}";
    }
}
