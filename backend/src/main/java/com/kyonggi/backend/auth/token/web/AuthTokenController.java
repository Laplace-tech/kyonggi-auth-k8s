package com.kyonggi.backend.auth.token.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kyonggi.backend.auth.token.dto.RefreshResponse;
import com.kyonggi.backend.auth.token.service.RefreshTokenService;
import com.kyonggi.backend.auth.token.service.RefreshTokenService.RotateResult;
import com.kyonggi.backend.auth.token.support.AuthCookieUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * POST: /auth/refresh
 * 
 * Reads refresh raw from HttpOnly cookie and performs rotation.
 * On success:
 * - set new refresh cookie (TTL follows the rememberMe policy of old refresh token)
 * - returns new access token in response body
 * 
 * Failures are handled by service via ApiException/ErrorCode (e.g., INVALID/EXPIRED/REUSED/REVOKED).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthTokenController {

    private final RefreshTokenService refreshTokenService;
    private final AuthCookieUtils cookieUtils;

    @PostMapping("/refresh")
    public RefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshRaw = cookieUtils.readRefreshCookie(request);

        // Service owns validation + concurrency + reuse detection.
        RotateResult result = refreshTokenService.rotate(refreshRaw);

        cookieUtils.setRefreshCookie(response, result.newRefreshRaw(), result.rememberMe());
        return new RefreshResponse(result.accessToken());
    }

}
