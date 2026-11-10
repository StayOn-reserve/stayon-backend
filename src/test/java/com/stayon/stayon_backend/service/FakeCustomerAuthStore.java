package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.repository.CustomerAuthStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory contract fake. Does not claim to execute or validate Redis Lua. */
class FakeCustomerAuthStore implements CustomerAuthStore {
    static class MutableClock extends Clock {
        Instant now = Instant.now();
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private record Window(int count, long expires) { }
    private static class Entry {
        Challenge value;
        long expires;
        int attempts;
        boolean ready;
        Entry(Challenge value, long expires) { this.value = value; this.expires = expires; }
    }
    final MutableClock clock;
    final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Entry> challenges = new HashMap<>();
    private final Map<String, String> active = new HashMap<>();
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    boolean unavailable;
    int ipIssueLimit = 50;
    int ipVerifyLimit = 100;
    int globalHourLimit = 1000;
    int globalDayLimit = 10000;

    FakeCustomerAuthStore(MutableClock clock) { this.clock = clock; }
    private long now() { return clock.instant().getEpochSecond(); }
    private void check() { if (unavailable) throw new IllegalStateException("test storage unavailable"); }
    private int count(String key) {
        var w = windows.get(key);
        return w == null || w.expires <= now() ? 0 : w.count;
    }
    private void increment(String key, int ttl) {
        var w = windows.get(key);
        windows.put(key, new Window(count(key) + 1, count(key) == 0 ? now() + ttl : w.expires));
    }
    @Override public synchronized boolean allowIssue(String ip) {
        check();
        if (count("ip:" + ip) >= ipIssueLimit || count("global-hour") >= globalHourLimit
                || count("global-day") >= globalDayLimit) return false;
        increment("ip:" + ip, 3600); increment("global-hour", 3600); increment("global-day", 86400);
        return true;
    }
    @Override public synchronized boolean allowVerification(String ip) {
        check();
        if (count("verify:" + ip) >= ipVerifyLimit) return false;
        increment("verify:" + ip, 3600);
        return true;
    }
    @Override public synchronized boolean begin(Challenge c) {
        check();
        var r = c.identity(); var p = c.phoneHash();
        if (cooldowns.getOrDefault("r:" + r, 0L) > now() || cooldowns.getOrDefault("p:" + p, 0L) > now()) return false;
        List<String> keys = List.of("rh:" + r, "rd:" + r, "ph:" + p, "pd:" + p);
        for (int i = 0; i < keys.size(); i++) if (count(keys.get(i)) >= (i % 2 == 0 ? 5 : 10)) return false;
        for (int i = 0; i < keys.size(); i++) increment(keys.get(i), i % 2 == 0 ? 3600 : 86400);
        String previous = active.put(r, c.id());
        if (previous != null) challenges.remove(previous);
        challenges.put(c.id(), new Entry(c, now() + 180));
        cooldowns.put("r:" + r, now() + 60); cooldowns.put("p:" + p, now() + 60);
        return true;
    }
    @Override public synchronized void sent(String id) {
        check();
        var e = challenges.get(id);
        if (e != null && e.expires > now() && id.equals(active.get(e.value.identity()))) e.ready = true;
    }
    @Override public synchronized void invalidate(String id) {
        check(); var e = challenges.remove(id);
        if (e != null) active.remove(e.value.identity(), id);
    }
    @Override public synchronized Optional<Challenge> findChallenge(String id) {
        check(); var e = challenges.get(id);
        return e == null || e.expires <= now() ? Optional.empty() : Optional.of(e.value);
    }
    @Override public synchronized boolean consume(Challenge c, String hash, Session s) {
        check(); var e = challenges.get(c.id());
        if (e == null || e.expires <= now() || !e.ready || !c.id().equals(active.get(c.identity())) || e.attempts >= 5) return false;
        var phoneFail = "fp:" + c.phoneHash(); var reservationFail = "fr:" + c.identity();
        if (count(phoneFail) >= 5 || count(reservationFail) >= 5) return false;
        e.attempts++;
        if (!c.codeHash().equals(hash)) {
            increment(phoneFail, 3600); increment(reservationFail, 3600);
            return false;
        }
        if (s.reservationId() != c.reservationId() || !s.phoneHash().equals(c.phoneHash()) || sessions.containsKey(s.id())) return false;
        sessions.put(s.id(), s); invalidate(c.id()); return true;
    }
    @Override public synchronized Optional<Session> findSession(String id) {
        check(); var s = sessions.get(id);
        return s == null || s.expiresAt() <= now() ? Optional.empty() : Optional.of(s);
    }
    @Override public synchronized void deleteSession(String id) { check(); sessions.remove(id); }
}
