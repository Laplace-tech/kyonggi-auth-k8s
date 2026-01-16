package com.kyonggi.backend.auth.token.support;

import java.time.Duration;
import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.kyonggi.backend.auth.config.AuthProperties;
import com.kyonggi.backend.auth.config.AuthProperties.Refresh;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Refresh Token 쿠키 유틸
 *
 * - refresh token은 보통 HttpOnly 쿠키로 내려 JS 접근을 막는다(XSS 완화).
 * - cookie 옵션(path/samesite/secure/maxAge)을 한 곳에서 통일한다.
 *
 * ResponseCookie
 * - rememberMe 여부와 무관하게 항상 Max-Age를 포함하는 persistent 쿠키로 내려준다.
 * - TTL만 rememberMeSeconds / sessionTtlSeconds로 분기한다.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieUtils {

    private final AuthProperties props;

    /** Refresh 쿠키 읽기 (없으면 null) */
    public String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) return null;

        String cookieName = props.refresh().cookieName();

        return Arrays.stream(cookies)
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .map(v -> v == null ? null : v.trim())
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Refresh 쿠키 세팅 
     * - rememberMe=true  -> 긴 TTL (Max-Age = rememberMeSeconds)
     * - rememberMe=false -> 짧은 TTL (Max-Age = sessionTtlSeconds)
     */
    public void setRefreshCookie(HttpServletResponse response, String refreshRaw, boolean rememberMe) {
        if (refreshRaw == null || refreshRaw.isBlank()) return;

        Refresh refreshProps = props.refresh(); 
        long ttlSeconds = rememberMe ? refreshProps.rememberMeSeconds() : refreshProps.sessionTtlSeconds();

        ResponseCookie cookie = baseRefreshCookie(refreshRaw)
                .maxAge(Duration.ofSeconds(ttlSeconds))
                .build();
                
       response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Refresh 쿠키 삭제 (속성(path/sameSite/secure)이 같아야 브라우저가 제대로 삭제함) */
    public void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = baseRefreshCookie("deleted")
                .maxAge(Duration.ZERO) // 즉시 만료
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseRefreshCookie(String value) {
        Refresh r = props.refresh();
        return ResponseCookie.from(r.cookieName(), value)
                .httpOnly(true)
                .secure(r.cookieSecure())
                .path(r.cookiePath())
                .sameSite(r.cookieSameSite().name());
    }

}
