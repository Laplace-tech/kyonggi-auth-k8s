package com.kyonggi.backend.auth.refresh;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.config.AuthProperties;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport.LoginResult;
import com.kyonggi.backend.global.ErrorCode;
import com.kyonggi.backend.infra.TestClockConfig;

import jakarta.servlet.http.Cookie;

@DisplayName("[Auth][Refresh] revoked/expired/user 보안 시나리오 통합 테스트")
class AuthRefreshSecurityIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired AuthProperties authProps;

    @BeforeEach
    void setUp() {
        createDefaultUser();
    }

    @Test
    @DisplayName("refresh: logout으로 revoke된 refresh로 refresh 시도 → 401 REFRESH_REVOKED")
    void refresh_after_logout_is_revoked() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String raw = login.refreshRaw();

        // 로그아웃으로 해당 refresh 토큰을 DB에서 revoke(LOGOUT) 처리
        AuthHttpSupport.performLogout(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, raw))
                .andReturn();

        // revoke된 토큰으로 refresh 시도 -> REFRESH_REVOKED
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, raw)),
                ErrorCode.REFRESH_REVOKED
        );
    }

    @Test
    @DisplayName("refresh: expires_at 지난 refresh → 401 REFRESH_EXPIRED (clock advance로 만료 만들기)")
    void refresh_expired_token_is_blocked() throws Exception {
        // 0) 로그인(rememberMe=false) -> refresh 발급
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String raw = login.refreshRaw();

        // 1) rememberMe=false면 TTL은 sessionTtlSeconds 고정
        long ttlSeconds = authProps.refresh().sessionTtlSeconds();

        // 2) 발급 시각(now) 기준으로 TTL+1초 이동 -> 만료 상태
        TestClockConfig.TEST_CLOCK.advance(Duration.ofSeconds(ttlSeconds + 1));

        // 3) 만료된 refresh로 refresh 시도 -> REFRESH_EXPIRED
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, raw)),
                ErrorCode.REFRESH_EXPIRED
        );
    }

    @Test
    @DisplayName("refresh: 로그인 후 유저 삭제(토큰 row도 함께 제거됨) → REFRESH_INVALID")
    void refresh_user_not_found_when_user_deleted_after_login() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String raw = login.refreshRaw();
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, EMAIL);

        // FK 때문에 refresh_tokens 먼저 삭제
        jdbc.update("delete from refresh_tokens where user_id = ?", userId);
        jdbc.update("delete from users where id = ?", userId);

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, raw)),
                ErrorCode.REFRESH_INVALID
        );
    }
}