package com.kyonggi.backend.auth.token.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kyonggi.backend.auth.token.domain.RefreshRevokeReason;
import com.kyonggi.backend.auth.token.service.RefreshTokenService;
import com.kyonggi.backend.auth.token.support.AuthCookieUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;


/**
 * POST: /auth/logout
 * 
 * Logout is idempotent:
 * - no cookie / unknown  cookie / already-revoked  => still 204
 * - always attempts to clear refresh cookie on client
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthLogoutController {
    
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieUtils cookieUtils;

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshRaw = cookieUtils.readRefreshCookie(request);

        // Server-side revoke is best-effort & idempotent (no cookie / not found / already revoked)
        refreshTokenService.revokeIfPresent(refreshRaw, RefreshRevokeReason.LOGOUT);

        // 클라이언트는 항상 '삭제' Set-Cookie를 받는 게 안전 (멱등)
        cookieUtils.clearRefreshCookie(response);
    }


}



