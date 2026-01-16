package com.kyonggi.backend.auth.login;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.domain.UserStatus;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport.LoginResult;
import com.kyonggi.backend.global.ErrorCode;


/**
 * LoginService.login(rawEmail, rawPassword, rememberMe) 통합 테스트
 *
 * 1) 입력 방어(하지만 현재는 컨트롤러 @Valid가 먼저 막아 400으로 떨어질 수 있음)
 * 2) 도메인 정책(validateKyonggiDomain)
 * 3) 유저 조회(findByEmail)
 * 4) 비밀번호 검증(matches)
 * 5) 계정 상태(ACTIVE)
 * 6) 성공(accessToken + refresh 쿠키, rememberMe 분기)
 */
@DisplayName("[Auth][Login] LoginService 통합 테스트")
class AuthLoginIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedUser() {
        createDefaultUser(); // 기본 유저 1명 심어두고, 각 테스트는 이 유저를 기준으로 "로그인 실패 정책"을 검증함
    }

    @Test
    @DisplayName("email blank → 400 (컨트롤러 검증) + Set-Cookie 없음")
    void blank_email_is_400_and_no_cookie() throws Exception {
        ResultActions actions = AuthHttpSupport.performLogin(mvc, "   ", PASSWORD, false);

        actions.andExpect(status().isBadRequest());
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)); 
    }

    @Test
    @DisplayName("password blank → 400 (컨트롤러 검증) + Set-Cookie 없음")
    void blank_password_is_400_and_no_cookie() throws Exception {
        ResultActions actions = AuthHttpSupport.performLogin(mvc, EMAIL, "   ", false);

        actions.andExpect(status().isBadRequest()); // 컨트롤러 단에서 @Valid로 감지
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("경기대 도메인 아님 → 400 EMAIL_DOMAIN_NOT_ALLOWED + Set-Cookie 없음")
    void rejects_non_kyonggi_domain() throws Exception {
        ResultActions actions = AuthHttpSupport.performLogin(mvc, "user@gmail.com", PASSWORD, false);

        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.EMAIL_DOMAIN_NOT_ALLOWED);
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("존재하지 않는 이메일 → 401 INVALID_CREDENTIALS + Set-Cookie 없음")
    void unknown_email_is_invalid_credentials() throws Exception {
        ResultActions actions = AuthHttpSupport.performLogin(mvc, "noone@kyonggi.ac.kr", "whatever123!", false);

        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.INVALID_CREDENTIALS);
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("비밀번호 틀림 → 401 INVALID_CREDENTIALS + Set-Cookie 없음")
    void wrong_password_is_invalid_credentials() throws Exception {
        ResultActions actions = AuthHttpSupport.performLogin(mvc, EMAIL, "wrong-password", false);

        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.INVALID_CREDENTIALS);
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("비활성 계정 → 403 ACCOUNT_DISABLED + Set-Cookie 없음")
    void non_active_is_account_disabled() throws Exception {
        UserStatus nonActive = pickNonActiveStatus();
        jdbc.update("update users set status = ? where email = ?", nonActive.name(), EMAIL);

        ResultActions actions = AuthHttpSupport.performLogin(mvc, EMAIL, PASSWORD, false);

        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.ACCOUNT_DISABLED);
        actions.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }
    
    @Test
    @DisplayName("login 성공: rememberMe=true 가 false 보다 refresh 쿠키 TTL(Max-Age)이 길다")
    void success_rememberMe_true_has_longer_cookie_ttl() throws Exception {
        LoginResult shortTtl = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);
        LoginResult longTtl = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, true);

        String shortCookie = findRefreshSetCookie(shortTtl.setCookieHeaders());
        String longCookie = findRefreshSetCookie(longTtl.setCookieHeaders());

        assertThat(shortTtl.accessToken()).isNotBlank();
        assertThat(longTtl.accessToken()).isNotBlank();
        assertThat(shortTtl.refreshRaw()).isNotBlank(); // ✅ 추가
        assertThat(longTtl.refreshRaw()).isNotBlank();  // ✅ 추가

        assertCookieHasHttpOnly(shortCookie);
        assertCookieHasHttpOnly(longCookie);

        long shortMaxAge = extractMaxAgeSeconds(shortCookie);
        long longMaxAge = extractMaxAgeSeconds(longCookie);

        assertThat(shortMaxAge)
                .as("rememberMe=false도 TTL 기반 쿠키면 Max-Age가 있어야 함. set-cookie=%s", shortCookie)
                .isGreaterThan(0);

        assertThat(longMaxAge)
                .as("rememberMe=true도 TTL 기반 쿠키면 Max-Age가 있어야 함. set-cookie=%s", longCookie)
                .isGreaterThan(0);

        assertThat(longMaxAge)
                .as("rememberMe=true TTL(Max-Age)이 rememberMe=false 보다 길어야 함")
                .isGreaterThan(shortMaxAge);
    }

    @Test
    @DisplayName("login 성공: 이메일 normalize(공백/대소문자) 되어도 성공")
    void success_email_normalized() throws Exception {
        String weirdEmail = "   " + EMAIL.toUpperCase() + "   ";

        LoginResult result = AuthFlowSupport.loginOk(mvc, weirdEmail, PASSWORD, false);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshRaw()).isNotBlank();
        
    }


    // ==============
    // helper methods
    // ==============
    private static UserStatus pickNonActiveStatus() {
        for (UserStatus s : UserStatus.values()) {
            if (s != UserStatus.ACTIVE) {
                return s;
            }
        }
        throw new IllegalStateException("UserStatus에 ACTIVE 외 값이 없으면 비활성 테스트를 할 수 없음");
    }

   
    private static String findRefreshSetCookie(List<String> setCookieHeaders) {
        for (String h : setCookieHeaders) {
            if (h != null && h.startsWith(AuthHttpSupport.REFRESH_COOKIE + "=")) {
                return h;
            }
        }
        throw new AssertionError("Set-Cookie에서 refresh 쿠키를 못 찾음. headers=" + setCookieHeaders);
    }

    private static void assertCookieHasHttpOnly(String setCookie) {
        assertThat(setCookie.toLowerCase()).contains("httponly");
    }

    private static long extractMaxAgeSeconds(String setCookie) {
        // 예: "KG_REFRESH=...; Max-Age=86400; Path=/auth; HttpOnly; SameSite=Lax"
        String lower = setCookie.toLowerCase();
        int idx = lower.indexOf("max-age=");
        if (idx < 0) {
            return -1L;
        }

        int start = idx + "max-age=".length();
        int end = start;
        while (end < setCookie.length() && Character.isDigit(setCookie.charAt(end))) {
            end++;
        }

        if (end == start) {
            return -1L;
        }
        return Long.parseLong(setCookie.substring(start, end));
    }
}