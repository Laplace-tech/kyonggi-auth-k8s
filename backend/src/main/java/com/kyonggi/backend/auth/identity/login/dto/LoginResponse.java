package com.kyonggi.backend.auth.identity.login.dto;

/**
 * 로그인 응답 DTO
 *
 * - accessToken: 응답 바디(JSON)로 반환 
 * - refreshToken: 응답 바디에 넣지 않고 HttpOnly 쿠키(Set-Cookie)로 반환
 *
 * 정책:
 * - accessToken은 짧은 수명, 매 요청 Authorization 헤더로 사용 -> JS가 접근 가능
 *   : Authrization: Bearer {accessToken}
 * - refreshToken은 재발급용이므로 JS 접근 차단(HttpOnly) + 서버(DB)에서 세션 통제
 *   : Set-Cookie: refreshToken={refreshToken}; HttpOnly; Secure; SameSite=Strict; Path=/auth/refresh; Max-Age=...
 */
public record LoginResponse(String accessToken) {}


