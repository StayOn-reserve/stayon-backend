package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.CustomerOtpProperties;
import com.stayon.stayon_backend.config.JwtProperties;
import com.stayon.stayon_backend.entity.Platform;
import com.stayon.stayon_backend.entity.Reservation;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import com.stayon.stayon_backend.repository.ReservationRepository;
import com.stayon.stayon_backend.security.CustomerJwtTokenProvider;
import com.stayon.stayon_backend.security.CustomerPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerOtpTests {
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final FakeCustomerAuthStore.MutableClock clock = new FakeCustomerAuthStore.MutableClock();
    private final FakeCustomerAuthStore store = new FakeCustomerAuthStore(clock);
    private final FakeSmsSender sms = new FakeSmsSender();
    private CustomerAuthService auth;
    private CustomerSessionService sessions;
    private CustomerAuthSecrets secrets;
    private CustomerJwtTokenProvider tokens;
    private Reservation reservation;

    static class FakeSmsSender implements SmsSender {
        record Delivery(String recipient, String otp, String id) { }
        final List<Delivery> deliveries = new ArrayList<>();
        boolean fail;
        @Override public void sendOtp(String phone, String code, String id) {
            deliveries.add(new Delivery(phone, code, id));
            if (fail) throw new IllegalStateException("test sender failure");
        }
        Delivery last() { return deliveries.getLast(); }
    }

    @BeforeEach
    void setUp() {
        var jwt = new CustomerJwtProperties();
        jwt.setSecret("customer-test-independent-signing-key-at-least-32-bytes");
        jwt.setIssuer("customer-test"); jwt.setAudience("customer-api-test");
        var admin = new JwtProperties(); admin.setSecret("admin-test-independent-signing-key-at-least-32-bytes");
        var otp = new CustomerOtpProperties(); otp.setHmacSecret("otp-test-independent-hmac-key-at-least-32-bytes");
        secrets = new CustomerAuthSecrets(otp, jwt);
        tokens = new CustomerJwtTokenProvider(jwt, admin);
        auth = new CustomerAuthService(reservations, store, secrets, sms, tokens, clock);
        sessions = new CustomerSessionService(store, clock);
        reservation = new Reservation(); reservation.setId(42L); reservation.setPlatform(Platform.CAMFIT);
        reservation.setReservationId("booking-1"); reservation.setCustomerPhone("+82 10-1234-5678");
        when(reservations.findByPlatformAndReservationId(Platform.CAMFIT, "booking-1")).thenReturn(Optional.of(reservation));
        when(reservations.findById(42L)).thenReturn(Optional.of(reservation));
    }

    private CustomerAuthService.ChallengeResponse issue() {
        return auth.issue(Platform.CAMFIT, "booking-1", "010-1234-5678", "127.0.0.1");
    }
    private CustomerPrincipal authenticate() {
        var c = issue();
        return tokens.parseAccessToken(auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1").accessToken());
    }
    private void status(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @Test
    void successfulOtpUsesRegisteredNormalizedPhoneAndCreatesReservationScopedSession() {
        var c = issue();
        assertEquals(180, c.expiresIn());
        assertEquals("01012345678", sms.last().recipient());
        assertTrue(sms.last().otp().matches("[0-9]{8}"));
        var saved = store.findChallenge(c.challengeId()).orElseThrow();
        assertNotEquals(sms.last().otp(), saved.codeHash());
        assertEquals(64, saved.codeHash().length());
        assertEquals(secrets.hmac("otp", c.challengeId() + ":" + sms.last().otp()), saved.codeHash());
        var result = auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1");
        assertEquals("Bearer", result.tokenType()); assertEquals(900, result.expiresIn());
        var p = tokens.parseAccessToken(result.accessToken());
        sessions.requireActive(p, Set.of("orders:create"));
        var session = store.findSession(p.sessionId()).orElseThrow();
        assertEquals(42L, session.reservationId());
        assertEquals(secrets.hmac("phone", "01012345678"), session.phoneHash());
        assertEquals(clock.instant().getEpochSecond() + 900, session.expiresAt());
        status(401, () -> auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "wrongPhone", "wrongPlatform"})
    void ineligibleReservationsHaveSameAcceptedResponseWithoutSending(String scenario) {
        var response = auth.issue(scenario.equals("wrongPlatform") ? Platform.TRIP_ELEVEN : Platform.CAMFIT,
                scenario.equals("unknown") ? "missing" : "booking-1",
                scenario.equals("wrongPhone") ? "01099999999" : "01012345678", "127.0.0.1");
        assertNotNull(UUID.fromString(response.challengeId())); assertEquals(180, response.expiresIn());
        assertTrue(sms.deliveries.isEmpty());
        status(401, () -> auth.verify(response.challengeId(), "12345678", "127.0.0.1"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "010-****-5678", "0101234xxxx", "***", "0212345678"})
    void missingOrMaskedRegisteredPhonesCannotAuthenticate(String phone) {
        reservation.setCustomerPhone(phone);
        assertEquals(180, issue().expiresIn()); assertTrue(sms.deliveries.isEmpty());
    }

    @Test void otpExpiresAfterThreeMinutes() {
        var c = issue(); clock.advance(180);
        status(401, () -> auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1"));
        assertTrue(store.sessions.isEmpty());
    }

    @Test void fiveFailuresBlockEvenCorrectCodeAndReissueDoesNotResetFailures() {
        var c = issue();
        String wrong = sms.last().otp().equals("00000000") ? "00000001" : "00000000";
        for (int i = 0; i < 5; i++) status(401, () -> auth.verify(c.challengeId(), wrong, "127.0.0.1"));
        status(401, () -> auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1"));
        clock.advance(60); var next = issue();
        status(401, () -> auth.verify(next.challengeId(), sms.last().otp(), "127.0.0.1"));
        assertTrue(store.sessions.isEmpty());
    }

    @Test void resendCooldownDoesNotSendAgainAndNewChallengeInvalidatesOldOne() {
        var first = issue(); String previousCode = sms.last().otp();
        var suppressed = issue(); assertEquals(1, sms.deliveries.size());
        status(401, () -> auth.verify(suppressed.challengeId(), previousCode, "127.0.0.1"));
        clock.advance(60); var second = issue(); assertEquals(2, sms.deliveries.size());
        status(401, () -> auth.verify(first.challengeId(), previousCode, "127.0.0.1"));
        assertNotNull(auth.verify(second.challengeId(), sms.last().otp(), "127.0.0.1").accessToken());
    }

    @Test void hourlyAndDailyIssuanceBudgetsSurviveResends() {
        for (int i = 0; i < 5; i++) { issue(); clock.advance(60); }
        issue(); assertEquals(5, sms.deliveries.size());
        clock.advance(3600);
        for (int i = 0; i < 5; i++) { issue(); clock.advance(60); }
        issue(); assertEquals(10, sms.deliveries.size());
        clock.advance(3600); issue(); assertEquals(10, sms.deliveries.size());
        clock.advance(86400); issue(); assertEquals(11, sms.deliveries.size());
    }

    @Test void phoneBudgetAppliesAcrossReservationsAndReservationBudgetAcrossPhoneInputs() {
        for (int i = 0; i < 5; i++) { issue(); clock.advance(60); }
        var other = new Reservation(); other.setId(43L); other.setReservationId("booking-2");
        other.setCustomerPhone(reservation.getCustomerPhone());
        when(reservations.findByPlatformAndReservationId(Platform.CAMFIT, "booking-2")).thenReturn(Optional.of(other));
        auth.issue(Platform.CAMFIT, "booking-2", "01012345678", "127.0.0.2");
        reservation.setCustomerPhone("01099999999");
        auth.issue(Platform.CAMFIT, "booking-1", "01099999999", "127.0.0.3");
        assertEquals(5, sms.deliveries.size());
    }

    @Test void ipAndGlobalLimitsApplyBeforeReservationLookup() {
        store.ipIssueLimit = 1; issue();
        status(429, this::issue);
        store.globalHourLimit = 1;
        status(429, () -> auth.issue(Platform.CAMFIT, "missing", "01012345678", "other-ip"));
        verify(reservations, times(1)).findByPlatformAndReservationId(any(), any());
        store.ipVerifyLimit = 1;
        status(401, () -> auth.verify(UUID.randomUUID().toString(), "12345678", "verify-ip"));
        status(429, () -> auth.verify(UUID.randomUUID().toString(), "12345678", "verify-ip"));
    }

    @Test void concurrentVerificationCreatesExactlyOneSession() throws Exception {
        var c = issue(); String code = sms.last().otp();
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) attempts.add(() -> {
                try { auth.verify(c.challengeId(), code, "127.0.0.1"); return true; }
                catch (ResponseStatusException e) { assertEquals(401, e.getStatusCode().value()); return false; }
            });
            long successes = 0;
            for (var future : pool.invokeAll(attempts)) if (future.get()) successes++;
            assertEquals(1, successes); assertEquals(1, store.sessions.size());
        }
    }

    @Test void smsFailureInvalidatesOtpAndDoesNotRetryOrResetCooldown() {
        sms.fail = true; var c = issue();
        status(401, () -> auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1"));
        issue(); assertEquals(1, sms.deliveries.size());
        clock.advance(60); sms.fail = false; var next = issue();
        assertNotNull(auth.verify(next.challengeId(), sms.last().otp(), "127.0.0.1").accessToken());
    }

    @Test void redisFailureIs503ForIssueVerifySessionLookupAndLogout() {
        var p = authenticate(); store.unavailable = true;
        status(503, this::issue);
        status(503, () -> auth.verify(UUID.randomUUID().toString(), "12345678", "127.0.0.1"));
        status(503, () -> sessions.requireActive(p, Set.of()));
        status(503, () -> sessions.logout(p));
    }

    @Test void changedRegisteredPhonePreventsOtpConsumption() {
        var c = issue(); reservation.setCustomerPhone("01099999999");
        status(401, () -> auth.verify(c.challengeId(), sms.last().otp(), "127.0.0.1"));
        assertTrue(store.sessions.isEmpty());
    }

    @Test void pendingDeliveryCannotBeVerifiedAndReadyTransitionFailureIsFailClosed() {
        SmsSender pendingSender = (phone, otp, id) -> status(401, () -> auth.verify(id, otp, "127.0.0.1"));
        auth = new CustomerAuthService(reservations, store, secrets, pendingSender, tokens, clock);
        var c = issue();
        assertTrue(store.findChallenge(c.challengeId()).isPresent());
        clock.advance(60);
        var failingStore = spy(store);
        doThrow(new IllegalStateException("test storage error")).when(failingStore).sent(anyString());
        auth = new CustomerAuthService(reservations, failingStore, secrets, sms, tokens, clock);
        status(503, this::issue);
        var delivery = sms.last();
        status(401, () -> auth.verify(delivery.id(), delivery.otp(), "127.0.0.1"));
    }

    @Test void sessionExpiresAndLogoutRevokesIt() {
        var p = authenticate(); sessions.logout(p); status(401, () -> sessions.requireActive(p, Set.of()));
        clock.advance(60); var next = authenticate(); clock.advance(900);
        status(401, () -> sessions.requireActive(next, Set.of()));
    }

    @Test void tokenAndSessionMustAgreeOnReservationIdSessionIdAndScopes() {
        var p = authenticate();
        status(401, () -> sessions.requireActive(new CustomerPrincipal(43L, p.sessionId(), p.scopes()), Set.of()));
        store.sessions.put(p.sessionId(), new Session(UUID.randomUUID().toString(), 42L, "hash", p.scopes(),
                clock.instant().getEpochSecond() + 900));
        status(401, () -> sessions.requireActive(p, Set.of()));
        store.sessions.put(p.sessionId(), new Session(p.sessionId(), 42L, "hash", Set.of("orders:read"),
                clock.instant().getEpochSecond() + 900));
        status(401, () -> sessions.requireActive(p, Set.of()));
        var readOnly = new CustomerPrincipal(42L, p.sessionId(), Set.of("orders:read"));
        status(403, () -> sessions.requireActive(readOnly, Set.of("orders:create")));
    }

    @Test void storageNeverContainsOriginalPhoneOrOtpAndHmacUsesSeparateDomains() {
        var c = issue(); var stored = store.findChallenge(c.challengeId()).orElseThrow();
        assertFalse(stored.toString().contains("01012345678"));
        assertNotEquals(secrets.hmac("phone", "12345678"), secrets.hmac("otp", "12345678"));
        assertNull(secrets.normalizePhone("01012x345678"));
        assertEquals("01012345678", secrets.normalizePhone("0082 10 1234 5678"));
    }
}
