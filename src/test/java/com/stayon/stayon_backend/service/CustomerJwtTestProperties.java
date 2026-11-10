package com.stayon.stayon_backend.service;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = {
        "customer.jwt.secret=customer-test-only-signing-key-at-least-32-bytes",
        "customer.jwt.issuer=stayon-customer-tests",
        "customer.jwt.audience=stayon-customer-api-tests",
        "customer.otp.hmac-secret=otp-test-only-independent-hmac-key-at-least-32-bytes"
        ,"payment.providers.rules.TEST_PG.enabled=true"
        ,"payment.providers.rules.TEST_PG.allow-all-sites=true"
        ,"payment.merchant.environment=SANDBOX"
})
public @interface CustomerJwtTestProperties {
}
