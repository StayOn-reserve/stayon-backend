package com.stayon.stayon_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Accommodation-local date policy; checkout day is excluded until checkout times are available. */
@Component
public class ReservationOrderWindow {
    private final Clock clock;
    private final ZoneId zone;
    private final int daysBeforeCheckIn;

    public ReservationOrderWindow(Clock clock,
            @Value("${customer.reservation.time-zone:Asia/Seoul}") String timeZone,
            @Value("${customer.reservation.order-days-before-check-in:0}") int daysBeforeCheckIn) {
        if (daysBeforeCheckIn < 0) throw new IllegalArgumentException("주문 허용 선행 일수는 음수일 수 없습니다.");
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
        this.daysBeforeCheckIn = daysBeforeCheckIn;
    }

    public boolean allows(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkIn.isBefore(checkOut)) return false;
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return !today.plusDays(daysBeforeCheckIn).isBefore(checkIn) && today.isBefore(checkOut);
    }
}
