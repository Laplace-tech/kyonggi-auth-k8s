package com.kyonggi.backend.auth.refresh;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.config.AuthProperties;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport.LoginResult;
import com.kyonggi.backend.auth.support.AuthHttpSupport.RefreshResult;
import com.kyonggi.backend.auth.token.domain.RefreshRevokeReason;
import com.kyonggi.backend.auth.token.domain.RefreshToken;
import com.kyonggi.backend.auth.token.support.TokenHashUtils;
import com.kyonggi.backend.global.ErrorCode;

import jakarta.servlet.http.Cookie;


@DisplayName("[Auth][Refresh] 리프레시 토큰 로테이션 통합 테스트")
class AuthRefreshRotationIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthProperties authProps;

    @BeforeEach
    void seedUser() {
        createDefaultUser();
    }

    @Test
    @DisplayName("로그인: refresh 쿠키 발급 + DB에는 refresh 해시 저장(rememberMe=false)")
    void login_saves_refresh_hash_in_db_rememberMe_false() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);

        // 응답에 access token, refresh token 있어야 함
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshRaw()).isNotBlank();

        // DB에는 raw가 아니라 hash로 저장되어야 함
        String hash = TokenHashUtils.sha256Hex(login.refreshRaw());
        Optional<RefreshToken> saved = refreshTokenRepository.findByTokenHash(hash);

        // 리프레쉬 토큰이 DB에 있어야 함 (NOT REVOKED, rememberMe: false)
        assertThat(saved).isPresent();
        assertThat(saved.get().isRevoked()).isFalse();
        assertThat(saved.get().isRememberMe()).isFalse();

        // 쿠키 TTL(Max-Age)은 rememberMe=false -> sessionTtlSeconds
        String setCookieLine = AuthHttpSupport.findSetCookieLine(login.setCookieHeaders(), AuthHttpSupport.REFRESH_COOKIE);
        long maxAge = extractMaxAgeSeconds(setCookieLine);

        assertThat(maxAge)
                .as("rememberMe=false 쿠키는 Max-Age가 sessionTtlSeconds와 같아야 함. set-cookie=%s", setCookieLine)
                .isEqualTo(authProps.refresh().sessionTtlSeconds());
    }

    @Test
    @DisplayName("로그인: refresh 쿠키 발급 + DB rememberMe=true 저장(rememberMe=true)")
    void login_saves_refresh_hash_in_db_rememberMe_true() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, true);

        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshRaw()).isNotBlank();

        String hash = TokenHashUtils.sha256Hex(login.refreshRaw());
        Optional<RefreshToken> saved = refreshTokenRepository.findByTokenHash(hash);

        assertThat(saved).isPresent();
        assertThat(saved.get().isRevoked()).isFalse();
        assertThat(saved.get().isRememberMe()).isTrue();

        // rememberMe=true -> rememberMeSeconds
        String setCookieLine = AuthHttpSupport.findSetCookieLine(login.setCookieHeaders(), AuthHttpSupport.REFRESH_COOKIE);
        long maxAge = extractMaxAgeSeconds(setCookieLine);

        assertThat(maxAge)
                .as("rememberMe=true 쿠키는 Max-Age가 rememberMeSeconds와 같아야 함. set-cookie=%s", setCookieLine)
                .isEqualTo(authProps.refresh().rememberMeSeconds());
    }

    @Test
    @DisplayName("리프레시: 정상 로테이션(새 refresh 발급) + 기존 refresh ROTATED로 폐기 + 새 row는 revoked=false")
    void refresh_rotates_and_revokes_old() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String oldRaw = login.refreshRaw();

        String oldHash = TokenHashUtils.sha256Hex(oldRaw);
        assertThat(refreshTokenRepository.findByTokenHash(oldHash)).isPresent();

        // refresh 호출 -> 새 토큰
        RefreshResult refreshed = AuthFlowSupport.refreshOk(mvc, oldRaw);
        String newRaw = refreshed.refreshRaw();

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(newRaw).isNotBlank();
        assertThat(newRaw).isNotEqualTo(oldRaw);

        // old는 ROTATED로 revoke
        Optional<RefreshToken> oldRowAfter = refreshTokenRepository.findByTokenHash(oldHash);
        assertThat(oldRowAfter).isPresent();
        assertThat(oldRowAfter.get().isRevoked()).isTrue();
        assertThat(oldRowAfter.get().getRevokeReason()).isEqualTo(RefreshRevokeReason.ROTATED);

        // new는 저장 + revoked=false
        String newHash = TokenHashUtils.sha256Hex(newRaw);
        Optional<RefreshToken> newRow = refreshTokenRepository.findByTokenHash(newHash);

        assertThat(newRow).isPresent();
        assertThat(newRow.get().isRevoked()).isFalse();
    }

    @Test
    @DisplayName("리프레시: 쿠키 없음 → 401 REFRESH_INVALID")
    void refresh_without_cookie_returns_refresh_invalid() throws Exception {
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, null),
                ErrorCode.REFRESH_INVALID
        );
    }

    @Test
    @DisplayName("리프레시: 미발급 refresh 토큰 → 401 REFRESH_INVALID")
    void refresh_unknown_token_returns_refresh_invalid() throws Exception {
        Cookie cookie = new Cookie(AuthHttpSupport.REFRESH_COOKIE, "definitely-not-issued-by-server");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, cookie),
                ErrorCode.REFRESH_INVALID
        );
    }

    @Test
    @DisplayName("리프레시: 로테이션 후 구 refresh 재사용 → 401 REFRESH_REUSED")
    void refresh_reuse_old_token_is_blocked() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        String oldRaw = login.refreshRaw();

        // 1회 refresh로 로테이션 발생
        AuthFlowSupport.refreshOk(mvc, oldRaw);

        // old 재사용 -> REFRESH_REUSED
        Cookie cookie = new Cookie(AuthHttpSupport.REFRESH_COOKIE, oldRaw);
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performRefresh(mvc, cookie),
                ErrorCode.REFRESH_REUSED
        );
    }

    @Test
    @DisplayName("리프레시: 로테이션 후 rememberMe 정책 유지(쿠키 TTL + DB rememberMe 유지)")
    void refresh_rotation_preserves_rememberMe_policy() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, true);
        String oldRaw = login.refreshRaw();

        RefreshResult refreshed = AuthFlowSupport.refreshOk(mvc, oldRaw);

        // 쿠키 TTL 유지(rememberMeSeconds)
        String setCookieLine = AuthHttpSupport.findSetCookieLine(refreshed.setCookieHeaders(), AuthHttpSupport.REFRESH_COOKIE);
        long maxAge = extractMaxAgeSeconds(setCookieLine);

        assertThat(maxAge)
                .as("rememberMe=true면 로테이션 후 새 쿠키도 rememberMeSeconds TTL이어야 함. set-cookie=%s", setCookieLine)
                .isEqualTo(authProps.refresh().rememberMeSeconds());

        // DB rememberMe 유지
        String newHash = TokenHashUtils.sha256Hex(refreshed.refreshRaw());
        Optional<RefreshToken> newRow = refreshTokenRepository.findByTokenHash(newHash);

        assertThat(newRow).isPresent();
        assertThat(newRow.get().isRememberMe()).isTrue();
        assertThat(newRow.get().isRevoked()).isFalse();
    }

    // -----------------
    // helper
    // -----------------
    private static long extractMaxAgeSeconds(String setCookieLine) {
        // 예: "KG_REFRESH=...; Max-Age=86400; Path=/auth; HttpOnly; SameSite=Lax"
        String lower = setCookieLine.toLowerCase();
        int idx = lower.indexOf("max-age=");
        if (idx < 0) return -1L;

        int start = idx + "max-age=".length();
        int end = start;
        while (end < setCookieLine.length() && Character.isDigit(setCookieLine.charAt(end))) {
            end++;
        }
        if (end == start) return -1L;

        return Long.parseLong(setCookieLine.substring(start, end));
    }
}