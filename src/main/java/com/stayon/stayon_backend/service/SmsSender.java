package com.stayon.stayon_backend.service;

/** Implementations must not log secrets and must use deliveryId as the provider idempotency key. */
@FunctionalInterface
public interface SmsSender {
    void sendOtp(String registeredPhone, String otp, String deliveryId);
}
