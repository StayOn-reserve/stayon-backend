package com.stayon.stayon_backend.repository;

import com.stayon.stayon_backend.config.CustomerOtpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class RedisCustomerAuthStore implements CustomerAuthStore {
    private final String PREFIX;
    private final StringRedisTemplate redis;
    private final CustomerOtpProperties properties;

    @Autowired
    public RedisCustomerAuthStore(StringRedisTemplate redis, CustomerOtpProperties properties) {
        this(redis, properties, "customer:{customer-auth}:");
    }

    RedisCustomerAuthStore(StringRedisTemplate redis, CustomerOtpProperties properties, String prefix) {
        this.redis = redis;
        this.properties = properties;
        this.PREFIX = prefix;
    }

    private String challenge(String id) { return PREFIX + "otp:" + id; }
    private String active(String identity) { return PREFIX + "active:" + identity; }

    private boolean execute(String lua, List<String> keys, String... args) {
        Long result = redis.execute(new DefaultRedisScript<>(lua, Long.class), keys, (Object[]) args);
        if (result == null) throw new IllegalStateException("Redis script returned no result");
        return result == 1;
    }

    @Override
    public boolean allowIssue(String ipHash) {
        return execute(CustomerAuthScripts.RATE, List.of(PREFIX + "issue-ip:" + ipHash,
                        PREFIX + "issue-global-hour", PREFIX + "issue-global-day"),
                String.valueOf(properties.getIssueIpHourlyLimit()), "3600",
                String.valueOf(properties.getGlobalHourlyLimit()), "3600",
                String.valueOf(properties.getGlobalDailyLimit()), "86400");
    }

    @Override
    public boolean allowVerification(String ipHash) {
        return execute(CustomerAuthScripts.RATE, List.of(PREFIX + "verify-ip:" + ipHash),
                String.valueOf(properties.getVerifyIpHourlyLimit()), "3600");
    }

    @Override
    public boolean begin(Challenge c) {
        return execute(CustomerAuthScripts.BEGIN, List.of(challenge(c.id()), active(c.identity()),
                        PREFIX + "cool-r:" + c.identity(), PREFIX + "cool-p:" + c.phoneHash(),
                        PREFIX + "issue-r-hour:" + c.identity(), PREFIX + "issue-r-day:" + c.identity(),
                        PREFIX + "issue-p-hour:" + c.phoneHash(), PREFIX + "issue-p-day:" + c.phoneHash()),
                c.id(), String.valueOf(c.reservationId()), c.phoneHash(), c.codeHash(), c.identity());
    }

    @Override
    public void sent(String id) {
        findChallenge(id).ifPresent(c -> execute(CustomerAuthScripts.SENT,
                List.of(challenge(id), active(c.identity()))));
    }

    @Override
    public void invalidate(String id) {
        findChallenge(id).ifPresent(c -> execute(CustomerAuthScripts.INVALIDATE,
                List.of(challenge(id), active(c.identity()))));
    }

    @Override
    public Optional<Challenge> findChallenge(String id) {
        Map<Object, Object> row = redis.opsForHash().entries(challenge(id));
        if (row.isEmpty()) return Optional.empty();
        return Optional.of(new Challenge((String) row.get("id"), Long.parseLong((String) row.get("reservation")),
                (String) row.get("phone"), (String) row.get("code"), (String) row.get("identity")));
    }

    @Override
    public boolean consume(Challenge c, String candidateHash, Session s) {
        return execute(CustomerAuthScripts.CONSUME, List.of(challenge(c.id()), active(c.identity()),
                        PREFIX + "fail-p:" + c.phoneHash(), PREFIX + "fail-r:" + c.identity(), PREFIX + "session:" + s.id()),
                candidateHash, s.id(), String.valueOf(s.reservationId()), s.phoneHash(),
                String.join(" ", s.scopes()), String.valueOf(s.expiresAt()));
    }

    @Override
    public Optional<Session> findSession(String id) {
        Map<Object, Object> row = redis.opsForHash().entries(PREFIX + "session:" + id);
        if (row.isEmpty()) return Optional.empty();
        return Optional.of(new Session((String) row.get("id"), Long.parseLong((String) row.get("reservation")),
                (String) row.get("phone"), Set.copyOf(Arrays.asList(((String) row.get("scope")).split(" "))),
                Long.parseLong((String) row.get("expires"))));
    }

    @Override
    public void deleteSession(String id) {
        redis.delete(PREFIX + "session:" + id);
    }
}
