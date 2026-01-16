package com.kyonggi.backend.auth.identity.signup.support;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OtpCodeGenerator {

    private final SecureRandom secureRandom;

    public String generate6Digits() {
        int n = secureRandom.nextInt(999_999) + 1; // 1~999999
        return String.format("%06d", n);          // 000001~999999
    }
}
