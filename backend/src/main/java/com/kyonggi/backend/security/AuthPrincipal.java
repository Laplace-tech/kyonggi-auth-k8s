package com.kyonggi.backend.security;

import com.kyonggi.backend.auth.domain.UserRole;

/**
 * SecurityContext에 저장되는 "인증된 사용자"의 최소 정보(Principal).
 *
 * 역할:
 * - Spring Security는 Authentication(= 인증 결과)을 SecurityContext에 보관한다.
 * - JwtAuthenticationFilter가 JWT 검증 성공 시 AuthPrincipal을 만들어 Authentication에 넣는다.
 *
 * 필드:
 * - userId: DB의 사용자 식별자
 * - role: 인가(권한 체크)용 역할(enum)
 */

public record AuthPrincipal(Long userId, UserRole role) {

    public AuthPrincipal {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        if (role == null) throw new IllegalArgumentException("role must not be null");
    }

    /** Spring Security 권한 문자열 규칙(ROLE_*) */
    public String authority() {
        return "ROLE_" + role.name();
    }
}
