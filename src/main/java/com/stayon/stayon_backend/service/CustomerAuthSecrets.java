package com.stayon.stayon_backend.service;

import com.stayon.stayon_backend.config.CustomerJwtProperties;
import com.stayon.stayon_backend.config.CustomerOtpProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class CustomerAuthSecrets {
    private final byte[] key;

    public CustomerAuthSecrets(CustomerOtpProperties properties, CustomerJwtProperties jwt) {
        String secret = properties.getHmacSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || secret.equals(jwt.getSecret())) {
            throw new IllegalArgumentException("독립적인 32바이트 이상의 고객 OTP HMAC 키가 필요합니다.");
        }
        if (properties.getIssueIpHourlyLimit() <= 0 || properties.getVerifyIpHourlyLimit() <= 0
                || properties.getGlobalHourlyLimit() <= 0 || properties.getGlobalDailyLimit() <= 0) {
            throw new IllegalArgumentException("OTP 요청 제한은 양수여야 합니다.");
        }
        key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hmac(String domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((domain + "\0" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC initialization failed", e);
        }
    }

    public boolean matches(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** Korean mobile numbers only. Do not strip arbitrary characters or accept masked numbers. */
    public String normalizePhone(String value) {
        if (value == null || value.length() > 30 || !value.matches("[+0-9 ()-]+")) return null;
        String phone = value.replaceAll("[ ()-]", "");
        if (phone.startsWith("+82")) phone = "0" + phone.substring(3);
        else if (phone.startsWith("0082")) phone = "0" + phone.substring(4);
        return phone.matches("010[0-9]{8}") ? phone : null;
    }
}
