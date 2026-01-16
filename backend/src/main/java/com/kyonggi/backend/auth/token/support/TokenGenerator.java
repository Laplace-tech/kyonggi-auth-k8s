package com.kyonggi.backend.auth.token.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Refresh Token 원문 생성기
 * 
 * - SecureRandom: 예측 불가능한 난수 필요
 * - Base64 URL-safe: 쿠키/헤더에 안전한 문자셋 (-, _) 사용, padding 제거
 */
@Component
@RequiredArgsConstructor
public class TokenGenerator {

    private static final int TOKEN_BYTES = 48;

    private final SecureRandom secureRandom;

    /** Refresh Token raw 생성 */
    public String generateRefreshToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
