package com.kyonggi.backend.auth.support;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

// AuthFlowSupport = "성공 플로우"를 짧게 만드는 고수준(HIGH-LEVEL) 유틸 (테스트 전용)
public final class AuthFlowSupport {
    private AuthFlowSupport() {}

    // OTP 메일 대기 기본값
    private static final Duration DEFAULT_OTP_AWAIT = Duration.ofSeconds(15);

    // "메일에서 추출한 OTP"가 만족해야 하는 계약(Contract)
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");

    /**
     * 로그인 성공을 기대하는 헬퍼 메서드
     *
     * - POST: /auth/login
     * - 200 OK + JSON 반환 강제
     * - accessToken(body) + refreshToken(Set-Cookie) 추출해서 LoginResult로 반환
     */
    public static AuthHttpSupport.LoginResult loginOk(
            MockMvc mvc,
            String email,
            String password,
            boolean rememberMe
    ) throws Exception {

        MvcResult res = AuthHttpSupport.performLogin(mvc, email, password, rememberMe)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String accessToken = extractAccessToken(res, "login");
        List<String> setCookieHeaders = extractSetCookieHeaders(res, "login");
        String refreshRaw = extractRequiredCookieValue(
                setCookieHeaders,
                AuthHttpSupport.REFRESH_COOKIE,
                "login"
        );

        return new AuthHttpSupport.LoginResult(accessToken, refreshRaw, setCookieHeaders);
    }

    /**
     * refresh 성공을 기대하는 헬퍼
     *
     * - POST: /auth/refresh 호출(Refresh 쿠키 포함)
     * - 200 OK + JSON 반환 강제
     * - 새 accessToken + 새 refreshRaw(Set-Cookie) 추출해서 RefreshResult로 반환
     */
    public static AuthHttpSupport.RefreshResult refreshOk(MockMvc mvc, String refreshRaw) throws Exception {
        assertThat(refreshRaw)
                .as("refreshOk 호출 시 refreshRaw는 비어있으면 안 됨")
                .isNotBlank();

        MvcResult res = AuthHttpSupport.performRefresh(
                        mvc,
                        new Cookie(AuthHttpSupport.REFRESH_COOKIE, refreshRaw)
                )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String accessToken = extractAccessToken(res, "refresh");
        List<String> setCookieHeaders = extractSetCookieHeaders(res, "refresh");

        // refresh 로테이션이면 여기서 "새 refresh"가 내려온다.
        String newRefreshRaw = extractRequiredCookieValue(
                setCookieHeaders,
                AuthHttpSupport.REFRESH_COOKIE,
                "refresh"
        );

        return new AuthHttpSupport.RefreshResult(accessToken, newRefreshRaw, setCookieHeaders);
    }

    // requestSignupOtpAndAwaitCode의 기본 타임아웃 버전 (기본 15초)
    public static String requestSignupOtpAndAwaitCode(
            MockMvc mvc,
            String requestEmail,
            String awaitEmail
    ) throws Exception {
        return requestSignupOtpAndAwaitCode(mvc, requestEmail, awaitEmail, DEFAULT_OTP_AWAIT);
    }

    // Duration 기반
    public static String requestSignupOtpAndAwaitCode(
            MockMvc mvc,
            String requestEmail,
            String awaitEmail,
            Duration timeout
    ) throws Exception {

        assertThat(timeout)
                .as("OTP 메일 대기 timeout은 null이면 안 됨")
                .isNotNull();

        AuthHttpSupport.performSignupOtpRequest(mvc, requestEmail)
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        // 메일 시스템은 비동기/지연이 있을 수 있으니 "폴링 + 타임아웃"이 중요
        String otp = MailhogSupport.awaitOtpFor(awaitEmail, timeout);

        assertThat(otp)
                .as("메일에서 추출한 OTP는 6자리 숫자여야 함. awaitEmail=%s, timeout=%s", awaitEmail, timeout)
                .isNotBlank()
                .matches(OTP_PATTERN);

        return otp;
    }

    // =====================
    //    private helpers
    // =====================

    private static String extractAccessToken(MvcResult res, String flowName) throws Exception {
        String token = AuthHttpSupport.readJson(res).path("accessToken").asText(null);

        assertThat(token)
                .as("[%s] 응답 JSON에 accessToken이 있어야 함", flowName)
                .isNotBlank();

        return token;
    }

    private static List<String> extractSetCookieHeaders(MvcResult res, String flowName) {
        List<String> headers = res.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(headers)
                .as("[%s] Set-Cookie 헤더가 최소 1개 이상 있어야 함", flowName)
                .isNotNull()
                .isNotEmpty();

        return headers;
    }

    private static String extractRequiredCookieValue(List<String> setCookieHeaders, String cookieName, String flowName) {
        String value = AuthHttpSupport.extractCookieValue(setCookieHeaders, cookieName);

        assertThat(value)
                .as("[%s] Set-Cookie에서 '%s' 쿠키 값을 추출해야 함. headers=%s", flowName, cookieName, setCookieHeaders)
                .isNotBlank();

        return value;
    }
}
