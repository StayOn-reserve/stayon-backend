package com.stayon.stayon_backend.repository;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

@Repository
public class RefreshTokenRepository {
    private final RedisTemplate<String, String> redisTemplate;

    public RefreshTokenRepository(
            RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    public void save(
            Long userId,
            String refreshToken,
            long expiration
    ) {
        String key = "refresh-token:" + userId;

        redisTemplate.opsForValue().set(
                key,
                refreshToken,
                expiration,
                TimeUnit.MILLISECONDS
        );
    }

    public String findByUserId(Long userId) {
        return redisTemplate
                .opsForValue()
                .get("refresh-token:" + userId);
    }

    public void delete(Long userId) {
        redisTemplate.delete("refresh-token:" + userId);
    }
}
