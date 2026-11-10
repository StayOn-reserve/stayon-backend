package com.stayon.stayon_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ReservationOrderWindowTests {
    private final LocalDate checkIn = LocalDate.of(2026, 11, 8);
    private final LocalDate checkOut = LocalDate.of(2026, 11, 10);

    private ReservationOrderWindow window(String instant, String zone, int advanceDays) {
        return new ReservationOrderWindow(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), zone, advanceDays);
    }

    @Test void checkInDayStartsAtAccommodationMidnight() {
        assertFalse(window("2026-11-07T14:59:59Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
        assertTrue(window("2026-11-07T15:00:00Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
    }

    @Test void checkoutDayStartsAtAccommodationMidnightAndIsExcluded() {
        assertTrue(window("2026-11-09T14:59:59Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
        assertFalse(window("2026-11-09T15:00:00Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
    }

    @Test void usesConfiguredZoneInsteadOfClockOrMachineZone() {
        assertFalse(window("2026-11-09T15:00:00Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
        assertTrue(window("2026-11-09T15:00:00Z", "UTC", 0).allows(checkIn, checkOut));
    }

    @Test void configuredAdvanceDaysOnlyExtendOpeningAndNeverCheckout() {
        assertFalse(window("2026-11-06T15:00:00Z", "Asia/Seoul", 0).allows(checkIn, checkOut));
        assertTrue(window("2026-11-06T15:00:00Z", "Asia/Seoul", 1).allows(checkIn, checkOut));
        assertFalse(window("2026-11-05T15:00:00Z", "Asia/Seoul", 1).allows(checkIn, checkOut));
        assertFalse(window("2026-11-09T15:00:00Z", "Asia/Seoul", 30).allows(checkIn, checkOut));
    }

    @ParameterizedTest @ValueSource(strings = {"noCheckIn", "noCheckOut", "sameDay", "inverted"})
    void ambiguousDatesFailClosed(String scenario) {
        var start = scenario.equals("noCheckIn") ? null : checkIn;
        var end = switch (scenario) {
            case "noCheckOut" -> null;
            case "sameDay" -> checkIn;
            case "inverted" -> checkIn.minusDays(1);
            default -> checkOut;
        };
        assertFalse(window("2026-11-08T00:00:00Z", "Asia/Seoul", 0).allows(start, end));
    }

    @Test void invalidConfigurationFailsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> window("2026-11-08T00:00:00Z", "Asia/Seoul", -1));
        assertThrows(java.time.DateTimeException.class, () -> window("2026-11-08T00:00:00Z", "invalid-zone", 0));
    }
}
