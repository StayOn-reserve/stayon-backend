package com.stayon.stayon_backend.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;

@Component
@RequiredArgsConstructor
public class PgRuntimeGuard {
    private final Environment environment;
    public void verify(String provider, boolean testOnly) {
        boolean reserved = provider != null && (provider.startsWith("TEST_") || provider.startsWith("FAKE_"));
        if ((testOnly || reserved) && !Set.copyOf(Arrays.asList(environment.getActiveProfiles())).equals(Set.of("test")))
            throw new IllegalStateException("Test PG components require the isolated test profile");
    }
}
