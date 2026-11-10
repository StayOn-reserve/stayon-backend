package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerOtpProperties;
import com.stayon.stayon_backend.repository.CustomerAuthStore;
import com.stayon.stayon_backend.repository.RedisCustomerAuthStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.server.ResponseStatusException;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Challenge;
import com.stayon.stayon_backend.repository.CustomerAuthStore.Session;
import com.stayon.stayon_backend.security.CustomerPrincipal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisCustomerAuthStoreTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisCustomerAuthStore store = new RedisCustomerAuthStore(redis, new CustomerOtpProperties());

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeSendsChallengeAndSessionToOneAtomicScriptAndClusterSlot() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var c = new CustomerAuthStore.Challenge("challenge-id", 42L, "phone-hmac", "code-hmac", "reservation-hmac");
        var s = new CustomerAuthStore.Session("session-id", 42L, "phone-hmac", Set.of("orders:read"), 1000);
        assertTrue(store.consume(c, "candidate-hmac", s));
        var script = ArgumentCaptor.forClass(RedisScript.class);
        var keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(Object[].class));
        assertEquals(5, keys.getValue().size());
        keys.getValue().forEach(key -> assertTrue(((String) key).startsWith("customer:{customer-auth}:")));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("'READY'"));
        assertTrue(lua.contains("'attempts'"));
        assertTrue(lua.contains("redis.call('HSET', KEYS[5]"));
        assertTrue(lua.contains("redis.call('DEL', KEYS[1], KEYS[2])"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void issuanceUsesOneScriptAndOnlyHashedSubjectKeys() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertFalse(store.begin(new CustomerAuthStore.Challenge("id", 42L, "phone-hmac", "code-hmac", "reservation-hmac")));
        var keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertEquals(8, keys.getValue().size());
        assertTrue(keys.getValue().contains("customer:{customer-auth}:issue-p-day:phone-hmac"));
        assertTrue(keys.getValue().contains("customer:{customer-auth}:issue-r-hour:reservation-hmac"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nullScriptResultIsNeverTreatedAsSuccess() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> store.allowIssue("ip-hmac"));
    }

    @Test
    void hashRowsRoundTripAndMissingSessionStaysMissing() {
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        when(hashes.entries("customer:{customer-auth}:session:id")).thenReturn(Map.of(
                "id", "id", "reservation", "42", "phone", "phone-hmac", "scope", "orders:read orders:create", "expires", "1000"));
        var session = store.findSession("id").orElseThrow();
        assertEquals(42L, session.reservationId());
        assertEquals(Set.of("orders:read", "orders:create"), session.scopes());
        assertEquals(1000, session.expiresAt());
        assertTrue(store.findSession("missing").isEmpty());
    }
}

/** Real Redis tests use only the explicitly authorized development server, never application configuration.
 * Kept in this existing test file because the sandbox denies creation of new source files.
 */
@Timeout(30)
class RedisCustomerAuthIntegrationTests {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisCustomerAuthStore store;
    private CustomerOtpProperties properties;
    private String prefix;

    private static LettuceConnectionFactory connection(int port) {
        var configuration = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO).build();
        var result = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), configuration);
        result.afterPropertiesSet();
        return result;
    }

    private RedisCustomerAuthStore namespacedStore(StringRedisTemplate template) throws Exception {
        // Keep namespace customization package-private: production callers retain the original constructor.
        var constructor = RedisCustomerAuthStore.class.getDeclaredConstructor(
                StringRedisTemplate.class, CustomerOtpProperties.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(template, properties, prefix);
    }

    @BeforeEach void setUp() throws Exception {
        prefix = "test:customer:{" + UUID.randomUUID() + "}:";
        factory = connection(6379);
        redis = new StringRedisTemplate(factory);
        try (var client = factory.getConnection()) {
            assertEquals("PONG", client.ping());
            String version = client.serverCommands().info("server").getProperty("redis_version");
            assertTrue(Integer.parseInt(version.split("\\.")[0]) >= 7,
                    "Redis 7 or later required, actual: " + version);
        }
        properties = new CustomerOtpProperties();
        store = namespacedStore(redis);
    }

    @AfterEach void tearDown() {
        try {
            if (redis != null && prefix != null) {
                var keys = new ArrayList<String>();
                try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
                    cursor.forEachRemaining(keys::add);
                }
                if (!keys.isEmpty()) redis.delete(keys);
            }
        } finally { if (factory != null) factory.destroy(); }
    }

    private Challenge challenge() { return new Challenge(UUID.randomUUID().toString(), 42L, "phone", "code", "booking"); }
    private Session session() {
        return new Session(UUID.randomUUID().toString(), 42L, "phone", Set.of("orders:read"),
                Clock.systemUTC().instant().getEpochSecond() + 900);
    }
    private Challenge ready() {
        var c = challenge(); assertTrue(store.begin(c)); store.sent(c.id()); return c;
    }
    private String otp(Challenge c) { return prefix + "otp:" + c.id(); }
    private void cooldownExpired() {
        redis.delete(List.of(prefix + "cool-r:booking", prefix + "cool-p:phone"));
    }
    private void ttl(String suffix, long expected) {
        long actual = redis.getExpire(prefix + suffix, TimeUnit.MILLISECONDS);
        assertTrue(actual > 0 && actual <= expected * 1000, "Unexpected TTL: " + suffix + ": " + actual);
        assertTrue(actual >= (expected - 5) * 1000, "TTL too short: " + suffix);
    }
    private void expires(String key) throws Exception {
        // Assert original TTL separately, then accelerate only this test's key and await real Redis expiry.
        assertTrue(redis.expire(key, Duration.ofMillis(150)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(redis.hasKey(key));
    }
    private CustomerPrincipal principal(Session s) { return new CustomerPrincipal(s.reservationId(), s.id(), s.scopes()); }
    private void status(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @Test void allProductionLuaScriptsLoadSuccessfully() throws Exception {
        var scripts = Class.forName("com.stayon.stayon_backend.repository.CustomerAuthScripts");
        try (var client = factory.getConnection()) {
            for (String name : List.of("RATE", "BEGIN", "SENT", "INVALIDATE", "CONSUME")) {
                var field = scripts.getDeclaredField(name);
                field.setAccessible(true);
                String lua = (String) field.get(null);
                String sha = client.scriptingCommands().scriptLoad(lua.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(List.of(true), client.scriptingCommands().scriptExists(sha), name);
            }
        }
    }

    @Test void beginPendingSentAndConsumeRoundTripWithConfiguredTtls() {
        var c = challenge(); assertTrue(store.begin(c));
        assertEquals(c, store.findChallenge(c.id()).orElseThrow());
        assertEquals("PENDING", redis.opsForHash().get(otp(c), "status"));
        assertFalse(store.consume(c, c.codeHash(), session()));
        ttl("otp:" + c.id(), 180); ttl("active:booking", 180);
        ttl("cool-r:booking", 60); ttl("cool-p:phone", 60);
        store.sent(c.id());
        assertEquals("READY", redis.opsForHash().get(otp(c), "status"));
        assertTrue(store.consume(c, c.codeHash(), session()));
    }

    @ParameterizedTest @ValueSource(strings = {"ip", "hour", "day"})
    void rateEnforcesIpAndGlobalBudgetsWithoutPartialIncrement(String budget) {
        properties.setIssueIpHourlyLimit(budget.equals("ip") ? 2 : 100);
        properties.setGlobalHourlyLimit(budget.equals("hour") ? 2 : 100);
        properties.setGlobalDailyLimit(budget.equals("day") ? 2 : 100);
        assertTrue(store.allowIssue("ip")); assertTrue(store.allowIssue("ip"));
        assertFalse(store.allowIssue(budget.equals("ip") ? "ip" : "another-ip"));
        assertEquals("2", redis.opsForValue().get(prefix + "issue-global-hour"));
        assertEquals("2", redis.opsForValue().get(prefix + "issue-global-day"));
        ttl("issue-ip:ip", 3600); ttl("issue-global-hour", 3600); ttl("issue-global-day", 86400);
    }

    @Test void verificationIpRateIsEnforced() {
        properties.setVerifyIpHourlyLimit(2);
        assertTrue(store.allowVerification("ip")); assertTrue(store.allowVerification("ip"));
        assertFalse(store.allowVerification("ip")); assertTrue(store.allowVerification("other"));
        ttl("verify-ip:ip", 3600);
    }

    @Test void invalidateDeletesChallengeAndActivePointer() {
        var c = ready(); store.invalidate(c.id()); store.sent(c.id());
        assertTrue(store.findChallenge(c.id()).isEmpty());
        assertFalse(redis.hasKey(prefix + "active:booking"));
        assertFalse(store.consume(c, c.codeHash(), session()));
    }

    @Test void otpExpiryDeniesConsumptionAndDelayedSentCannotReviveIt() throws Exception {
        var c = ready(); ttl("otp:" + c.id(), 180); expires(otp(c)); store.sent(c.id());
        assertTrue(store.findChallenge(c.id()).isEmpty());
        var s = session(); assertFalse(store.consume(c, c.codeHash(), s));
        assertTrue(store.findSession(s.id()).isEmpty());
    }

    @Test void reissueInvalidatesOldOtpAndDelayedCallbacksCannotHarmReplacement() {
        var old = ready(); assertFalse(store.begin(challenge())); cooldownExpired();
        var next = ready(); store.sent(old.id()); store.invalidate(old.id());
        assertTrue(store.findChallenge(old.id()).isEmpty());
        assertEquals(otp(next), redis.opsForValue().get(prefix + "active:booking"));
        assertFalse(store.consume(old, old.codeHash(), session()));
        assertTrue(store.consume(next, next.codeHash(), session()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"r-hour:booking", "r-day:booking", "p-hour:phone", "p-day:phone"})
    void subjectHourlyAndDailyBudgetsRejectAtBoundary(String budget) {
        int limit = budget.contains("hour") ? 5 : 10;
        String key = prefix + "issue-" + budget;
        redis.opsForValue().set(key, String.valueOf(limit - 1), Duration.ofHours(1));
        var c = ready(); assertEquals(String.valueOf(limit), redis.opsForValue().get(key));
        cooldownExpired(); assertFalse(store.begin(challenge()));
        assertEquals(c, store.findChallenge(c.id()).orElseThrow());
        assertEquals("1", redis.opsForValue().get(prefix + "issue-" +
                (budget.startsWith("r-") ? "p-hour:phone" : "r-hour:booking")));
    }

    @Test void issuanceSetsHourlyAndDailyTtls() {
        ready();
        for (String subject : List.of("r-hour:booking", "p-hour:phone")) ttl("issue-" + subject, 3600);
        for (String subject : List.of("r-day:booking", "p-day:phone")) ttl("issue-" + subject, 86400);
    }

    @Test void fiveFailuresLockChallengeEvenWithCorrectCode() {
        var c = ready();
        for (int i = 0; i < 5; i++) assertFalse(store.consume(c, "wrong", session()));
        assertFalse(store.consume(c, c.codeHash(), session()));
        assertEquals("5", redis.opsForHash().get(otp(c), "attempts"));
        assertEquals("5", redis.opsForValue().get(prefix + "fail-p:phone"));
        ttl("fail-p:phone", 3600); ttl("fail-r:booking", 3600);
    }

    @Test void reissueDoesNotResetCumulativeFailures() {
        var old = ready();
        for (int i = 0; i < 4; i++) assertFalse(store.consume(old, "wrong", session()));
        cooldownExpired(); var next = ready();
        assertFalse(store.consume(next, "wrong", session()));
        assertFalse(store.consume(next, next.codeHash(), session()));
        assertEquals("1", redis.opsForHash().get(otp(next), "attempts"));
        assertEquals("5", redis.opsForValue().get(prefix + "fail-r:booking"));
    }

    @Test void consumeCreatesQueryableSessionExactlyOnce() {
        var c = ready(); var s = session(); assertTrue(store.consume(c, c.codeHash(), s));
        assertEquals(s, store.findSession(s.id()).orElseThrow()); ttl("session:" + s.id(), 900);
        assertTrue(store.findChallenge(c.id()).isEmpty()); assertFalse(redis.hasKey(prefix + "active:booking"));
        var second = session(); assertFalse(store.consume(c, c.codeHash(), second));
        assertTrue(store.findSession(second.id()).isEmpty());
    }

    @Test void sessionCollisionDoesNotConsumeOtpOrOverwriteSession() {
        var c = ready(); var s = session();
        redis.opsForValue().set(prefix + "session:" + s.id(), "existing", Duration.ofSeconds(30));
        assertFalse(store.consume(c, c.codeHash(), s));
        assertEquals("existing", redis.opsForValue().get(prefix + "session:" + s.id()));
        assertTrue(store.findChallenge(c.id()).isPresent());
        assertTrue(store.consume(c, c.codeHash(), session()));
    }

    @Test void parallelConsumptionHasOneWinnerAndNoObservablePartialTransition() throws Exception {
        var c = ready(); var candidates = new ArrayList<Session>();
        for (int i = 0; i < 16; i++) candidates.add(session());
        var gate = new CountDownLatch(1); var waiting = new CountDownLatch(17);
        try (var executor = Executors.newFixedThreadPool(17)) {
            var results = new ArrayList<Future<Boolean>>();
            for (var s : candidates) results.add(executor.submit(() -> {
                waiting.countDown(); assertTrue(gate.await(5, TimeUnit.SECONDS));
                return store.consume(c, c.codeHash(), s);
            }));
            var keys = new ArrayList<>(List.of(otp(c), prefix + "active:booking"));
            candidates.forEach(s -> keys.add(prefix + "session:" + s.id()));
            var observer = executor.submit(() -> {
                waiting.countDown(); assertTrue(gate.await(5, TimeUnit.SECONDS));
                var snapshot = new DefaultRedisScript<>("""
                        local sessions = 0
                        for i=3,#KEYS do sessions = sessions + redis.call('EXISTS', KEYS[i]) end
                        local otp = redis.call('EXISTS', KEYS[1])
                        local active = redis.call('EXISTS', KEYS[2])
                        if (otp == 1 and active == 1 and sessions == 0)
                            or (otp == 0 and active == 0 and sessions == 1) then return 1 end
                        return 0
                        """, Long.class);
                for (int i = 0; i < 100; i++) assertEquals(1L, redis.execute(snapshot, keys));
                return true;
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); gate.countDown();
            int winners = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
            assertTrue(observer.get(10, TimeUnit.SECONDS)); assertEquals(1, winners);
            assertEquals(1, candidates.stream().filter(s -> store.findSession(s.id()).isPresent()).count());
            assertTrue(store.findChallenge(c.id()).isEmpty()); assertFalse(redis.hasKey(prefix + "active:booking"));
        } finally { gate.countDown(); }
    }

    @Test void sessionExpiryAndLogoutDenyAccess() throws Exception {
        var c = ready(); var s = session(); assertTrue(store.consume(c, c.codeHash(), s));
        var service = new CustomerSessionService(store, Clock.systemUTC());
        service.requireActive(principal(s), s.scopes()); expires(prefix + "session:" + s.id());
        status(401, () -> service.requireActive(principal(s), s.scopes()));
        cooldownExpired(); var next = ready(); var live = session();
        assertTrue(store.consume(next, next.codeHash(), live));
        service.requireActive(principal(live), live.scopes()); service.logout(principal(live));
        assertTrue(store.findSession(live.id()).isEmpty());
        status(401, () -> service.requireActive(principal(live), live.scopes()));
    }

    @Test void absoluteSessionExpiryDeniesAccessEvenWhileRedisKeyExists() {
        var c = ready(); var s = session(); assertTrue(store.consume(c, c.codeHash(), s));
        var expiredClock = Clock.fixed(java.time.Instant.ofEpochSecond(s.expiresAt()), java.time.ZoneOffset.UTC);
        var service = new CustomerSessionService(store, expiredClock);
        assertTrue(store.findSession(s.id()).isPresent());
        status(401, () -> service.requireActive(principal(s), s.scopes()));
    }

    @Test void connectionFailureFailsClosedWithoutStoppingSharedRedis() throws Exception {
        // Reserve an ephemeral TCP port without listening; no server is stopped or reconfigured.
        try (var unavailable = java.nio.channels.SocketChannel.open()) {
            unavailable.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            int port = ((java.net.InetSocketAddress) unavailable.getLocalAddress()).getPort();
            var brokenFactory = connection(port);
            try {
                var broken = namespacedStore(new StringRedisTemplate(brokenFactory));
                assertThrows(RuntimeException.class, () -> broken.allowIssue("ip"));
                var service = new CustomerSessionService(broken, Clock.systemUTC());
                status(503, () -> service.requireActive(principal(session()), Set.of("orders:read")));
                status(503, () -> service.logout(principal(session())));
            } finally { brokenFactory.destroy(); }
        }
        assertTrue(store.allowIssue("still-available"));
    }
}
