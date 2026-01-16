package com.kyonggi.backend.auth.identity.signup.support;

import com.kyonggi.backend.global.ApiException;
import com.kyonggi.backend.global.ErrorCode;

public class KyonggiEmailUtils {

    private static final String DOMAIN = "@kyonggi.ac.kr";

    // 입력 이메일을 trim + 소문자로 정규화
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
    /**
     * 경기대 이메일 도메인 검증
     * - 비즈니스 규칙: @kyonggi.ac.kr 이메일만 가입 가능
     * - @Email 형식 검증은 DTO에서, 도메인 제한은 서비스 정책에서
     */
     public static void validateKyonggiDomain(String email) {
        String normalized = normalize(email);
        if (normalized == null || !normalized.endsWith(DOMAIN)) {
            throw new ApiException(ErrorCode.EMAIL_DOMAIN_NOT_ALLOWED);
        }
    }
}
