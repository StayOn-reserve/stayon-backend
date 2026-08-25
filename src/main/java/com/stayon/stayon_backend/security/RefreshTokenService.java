package com.stayon.stayon_backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final StringRedisTemplate redisTemplate;

    public void save(Long userId, String refreshToken, long expiration) {
        String key = "refresh:" + userId;

        redisTemplate.opsForValue().set(
                key,
                refreshToken,
                expiration,
                TimeUnit.MILLISECONDS
        );
    }

    public String get(Long userId) {
        return redisTemplate.opsForValue()
                .get("refresh:" + userId);
    }

    public void delete(Long userId) {
        redisTemplate.delete("refresh:" + userId);
    }
}
