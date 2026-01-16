package com.kyonggi.backend.auth.logout;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport.LoginResult;
import com.kyonggi.backend.auth.token.domain.RefreshRevokeReason;
import com.kyonggi.backend.auth.token.domain.RefreshToken;
import com.kyonggi.backend.auth.token.support.TokenHashUtils;

import jakarta.servlet.http.Cookie;

@DisplayName("[Auth][Logout] 로그아웃(/auth/logout) 통합 테스트")
class AuthLogoutIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;

    @BeforeEach
    void setUp() {
        createDefaultUser();
    }

    @Test
    @DisplayName("logout: refresh 쿠키 있음 → DB 토큰 revoke(LOGOUT) + 쿠키 삭제(Max-Age=0)")
    void logout_with_cookie_revokes_token_and_clears_cookie() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String refreshRaw = login.refreshRaw();

        String hash = TokenHashUtils.sha256Hex(refreshRaw);
        assertThat(refreshTokenRepository.findByTokenHash(hash)).isPresent();

        MvcResult res = AuthHttpSupport.performLogout(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, refreshRaw))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String setCookieLine = AuthHttpSupport.findSetCookieLine(
                res.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                AuthHttpSupport.REFRESH_COOKIE
        );
        AuthHttpSupport.assertRefreshCookieCleared(setCookieLine);

        Optional<RefreshToken> row = refreshTokenRepository.findByTokenHash(hash);
        assertThat(row).isPresent();
        assertThat(row.get().isRevoked()).isTrue();
        assertThat(row.get().getRevokeReason()).isEqualTo(RefreshRevokeReason.LOGOUT);
    }

    @Test
    @DisplayName("logout: 쿠키 없음 → 204 (idempotent) + 쿠키 삭제 헤더는 내려옴")
    void logout_without_cookie_is_idempotent_and_still_clears_cookie() throws Exception {
        MvcResult res = AuthHttpSupport.performLogout(mvc, null)
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String setCookieLine = AuthHttpSupport.findSetCookieLine(
                res.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                AuthHttpSupport.REFRESH_COOKIE
        );
        AuthHttpSupport.assertRefreshCookieCleared(setCookieLine);
    }

    @Test
    @DisplayName("logout: 미발급 쿠키 → 204 (idempotent) + 쿠키 삭제(Max-Age=0)")
    void logout_with_unknown_cookie_is_idempotent_and_clears_cookie() throws Exception {
        MvcResult res = AuthHttpSupport.performLogout(mvc, new Cookie(AuthHttpSupport.REFRESH_COOKIE, "not-issued"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String setCookieLine = AuthHttpSupport.findSetCookieLine(
                res.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                AuthHttpSupport.REFRESH_COOKIE
        );
        AuthHttpSupport.assertRefreshCookieCleared(setCookieLine);
    }
}
