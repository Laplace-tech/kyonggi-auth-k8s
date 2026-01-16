package com.kyonggi.backend.auth.identity.login.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kyonggi.backend.auth.identity.login.dto.LoginRequest;
import com.kyonggi.backend.auth.identity.login.dto.LoginResponse;
import com.kyonggi.backend.auth.identity.login.service.LoginService;
import com.kyonggi.backend.auth.identity.login.service.LoginService.LoginResult;
import com.kyonggi.backend.auth.token.support.AuthCookieUtils;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 API
 *
 * - 요청(JSON) 검증: @Valid DTO
 * - 핵심 로직: 서비스로 위임(인증/정책/토큰 발급)
 * - 응답 변환:
 *   - accessToken: 바디
 *   - refreshToken: HttpOnly 쿠키
 */

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthLoginController {

    private final LoginService loginService;
    private final AuthCookieUtils cookieUtils; // refresh token 쿠키를 생성/삭제하는 유틸

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {

        LoginResult result = loginService.login(
                req.email(),
                req.password(),
                req.rememberMeOrFalse()
        );

        cookieUtils.setRefreshCookie(response, result.refreshRaw(), result.rememberMe());
        return new LoginResponse(result.accessToken());
    }
}

