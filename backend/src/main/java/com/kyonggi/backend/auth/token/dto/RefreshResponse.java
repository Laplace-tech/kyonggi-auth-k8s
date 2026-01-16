package com.kyonggi.backend.auth.token.dto;


/**
 * /auth/refresh 응답 바디
 * - access token(JWT): 응답 JSON 바디로 반환 (클라이언트가 메모리/스토리지 정책에 따라 보관)
 * - refresh token: HttpOnly 쿠키로만 내려감 (JS 접근 차단 -> XSS 표면을 줄임)
 */
public record RefreshResponse(String accessToken) {}