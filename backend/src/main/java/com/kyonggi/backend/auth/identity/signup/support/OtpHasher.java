package com.kyonggi.backend.auth.identity.signup.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.kyonggi.backend.auth.config.OtpProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OtpHasher {

    private final OtpProperties props;

    public String hash(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("OTP raw code must not be blank");
        }
        return hmacSha256Hex(props.hmacSecret(), raw);
    }

    public boolean matches(String raw, String hash) {
        if (raw == null || raw.isBlank() || hash == null || hash.isBlank()) {
            return false;
        }
        String expected = hash(raw);
        // constant-time compare
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}
