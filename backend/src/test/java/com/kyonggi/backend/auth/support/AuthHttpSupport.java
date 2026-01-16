package com.kyonggi.backend.auth.support;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyonggi.backend.global.ErrorCode;

import jakarta.servlet.http.Cookie;

/**
 * HTTP 응답 예시 ("Set-Cookie 헤더는 여러 개"일 수 있음)
 * ============================================================================================
 * HTTP/1.1 200 OK                                                                            ㅣ
 * Set-Cookie: KG_REFRESH=eyJhbGciOiJIUzI1NiJ9.abc.def; Path=/auth; HttpOnly; SameSite=Lax    ㅣ
 * Content-Type: application/json                                                             ㅣ
 * .                                                                                          ㅣ
 * .                                                                                          ㅣ
 * {"accessToken":"eyJhbGzY3MT...4PKGjIiMZ_SZ3KiJ6yYrToZ3Os"}                                 ㅣ     
 * ============================================================================================
 * 
 * 자바에서 추출하는 값: (그대로 LoginResult로 만들어 반환) 
 * - accessToken = "eyJhbGzY3MT...4PKGjIiMZ_SZ3KiJ6yYrToZ3Os"
 * - setCookieHeaders = List.of("KG_REFRESH=eyJhbGciOiJIUzI1NiJ9.abc.def; Path=/auth; HttpOnly; SameSite=Lax");
 * - refreshRaw = "eyJhbGciOiJIUzI1NiJ9.abc.def";
 */


// AuthHttpSupport = "HTTP 요청/응답" 관련 저수준(LOW-LEVEL) 유틸.
public final class AuthHttpSupport {
    private AuthHttpSupport() {}

    private static final ObjectMapper om = new ObjectMapper();
    
    // ✅ 회원가입 OTP/완료 엔드포인트 상수
    public static final String SIGNUP_OTP_REQUEST_ENDPOINT = "/auth/signup/otp/request";
    public static final String SIGNUP_OTP_VERIFY_ENDPOINT  = "/auth/signup/otp/verify";
    public static final String SIGNUP_COMPLETE_ENDPOINT    = "/auth/signup/complete";

    // ✅ 로그인/토큰/내정보 엔드포인트 상수
    public static final String LOGIN_ENDPOINT = "/auth/login";
    public static final String REFRESH_ENDPOINT = "/auth/refresh";
    public static final String LOGOUT_ENDPOINT = "/auth/logout";
    public static final String ME_ENDPOINT = "/auth/me";

    // ✅ Refresh 쿠키 이름(application-test.yml의 app.auth.refresh.cookie-name 과 반드시 동일해야 함)
    public static final String REFRESH_COOKIE = "KG_REFRESH";

    // ✅ 로그인/리프레시 결과를 테스트에서 편하게 다루기 위한 record
    public record LoginResult(String accessToken, String refreshRaw, List<String> setCookieHeaders) {}
    public record RefreshResult(String accessToken, String refreshRaw, List<String> setCookieHeaders) {}


    // POST: /auth/signup/otp/request
    public static ResultActions performSignupOtpRequest(MockMvc mvc, String email) throws Exception {
        return mvc.perform(post(SIGNUP_OTP_REQUEST_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s"}
                        """.formatted(email)));
    }


    // POST: /auth/signup/otp/verify
    public static ResultActions performSignupOtpVerify(MockMvc mvc, String email, String code6) throws Exception {
        return mvc.perform(post(SIGNUP_OTP_VERIFY_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","code":"%s"}
                        """.formatted(email, code6)));
    }

    // POST: /auth/signup/complete
    public static ResultActions performSignupComplete(MockMvc mvc, String email, String password, String passwordConfirm, String nickname) throws Exception {
        return mvc.perform(post(SIGNUP_COMPLETE_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","passwordConfirm":"%s","nickname":"%s"}
                        """.formatted(email, password, passwordConfirm, nickname)));
    }

    // POST: /auth/login
    public static ResultActions performLogin(MockMvc mvc, String email, String password, boolean rememberMe) throws Exception {
        return mvc.perform(post(LOGIN_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","rememberMe":%s}
                        """.formatted(email, password, rememberMe)));
    }

    // POST: /auth/refresh
    public static ResultActions performRefresh(MockMvc mvc, Cookie cookieOrNull) throws Exception {
        var req = post(REFRESH_ENDPOINT);
        if (cookieOrNull != null) 
            req.cookie(cookieOrNull);
        
        return mvc.perform(req);
    }

    // POST: /auth/logout
    public static ResultActions performLogout(MockMvc mvc, Cookie cookieOrNull) throws Exception {
        var req = post(LOGOUT_ENDPOINT);
        if (cookieOrNull != null) req.cookie(cookieOrNull);
        return mvc.perform(req);
    }

    // GET: /auth/me
    public static ResultActions performMe(MockMvc mvc, String authorizationHeaderOrNull) throws Exception {
        var req = get(ME_ENDPOINT); 
        if (authorizationHeaderOrNull != null) 
            req.header(HttpHeaders.AUTHORIZATION, authorizationHeaderOrNull);
        return mvc.perform(req);
    }

    // "Bearer " 접두사를 붙여서 Authorization 헤더 값 만들기
    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }



    /**
     * 에러 응답 공통 검증:
     * - HTTP status가 ErrorCode에 정의된 status와 일치해야 함
     * - body JSON에 {"code": "..."} 가 있어야 하고 그 값이 ErrorCode.name()과 일치해야 함
     */
    public static MvcResult expectErrorWithCode(ResultActions actions, ErrorCode code) throws Exception {
        MvcResult res = actions
                .andExpect(status().is(code.status().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertErrorCode(res, code.name());
        return res;
    }

    /**
     * 에러 바디의 code 필드를 직접 검증하는 저수준 메서드
     * - expectErrorWithCode 내부에서 쓰이거나
     * - status는 다른 기준으로 보고 code만 확인하고 싶을 때 사용
     */
    public static void assertErrorCode(MvcResult res, String code) throws Exception {
        JsonNode json = readJson(res);
        assertThat(json.get("code"))
                .as("error response must contain $.code")
                .isNotNull();
        assertThat(json.get("code").asText()).isEqualTo(code);
    }



    // 응답 body(String)를 JSON으로 파싱해서 JsonNode로 반환
    public static JsonNode readJson(MvcResult res) throws Exception {
        return om.readTree(res.getResponse().getContentAsString());
    }

    /**
     * Set-Cookie 라인에서 "쿠키 값"만 추출
     *  : "KG_REFRESH=aaa.bbb.ccc; Path=/auth; HttpOnly" -> "aaa.bbb.ccc"
     */
    public static String extractCookieValue(List<String> setCookieHeaders, String cookieName) {
        String line = findSetCookieLine(setCookieHeaders, cookieName);
        String first = line.split(";", 2)[0]; // "KG_REFRESH=aaa.bbb.ccc"
        int idx = first.indexOf('=');
        if (idx < 0) throw new IllegalStateException("Malformed Set-Cookie: " + line);
        return first.substring(idx + 1);
    }

    /** 
     * Set-Cookie 헤더는 여러 개일 수 있으니,
     * "cookieName="으로 시작하는 라인을 찾아서 반환한다.
     * 
     * setCookieHeaders = List.of(
     *      "KG_REFRESH=aaa.bbb.ccc; Path=/auth; HttpOnly; SameSite=Lax",
     *      "JSESSIONID=123456789; Path=/; HttpOnly"
     * ); 
     * cookieName = "KG_REFRESH"
     */
    public static String findSetCookieLine(List<String> setCookieHeaders, String cookieName) {
        assertThat(setCookieHeaders)
                .as("Set-Cookie header missing")
                .isNotNull()
                .isNotEmpty();

        // "COOKIE_NAME="로 시작하는 라인 찾기 (가장 안정적)
        return setCookieHeaders.stream()
                .filter(header -> header != null)
                .map(String::trim)
                .filter(header -> header.startsWith(cookieName + "="))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Set-Cookie line for " + cookieName + " not found. headers=" + setCookieHeaders
                ));
    }

    // Set-Cookie 리스트에 특정 쿠키가 존재하는지 검사
    public static void assertHasCookie(List<String> setCookieHeaders, String cookieName) {
        assertThat(setCookieHeaders)
                .as("Set-Cookie should contain " + cookieName)
                .isNotNull()
                .anyMatch(h -> h != null && h.startsWith(cookieName + "="));
    }

    /**
     * Refresh 쿠키 정책 검증
     *
     * - HttpOnly: JS 접근 차단
     * - Path=/auth: auth 범위에만 쿠키 전달
     * - SameSite=Lax: CSRF 완화
     * 
     * ✅ rememberMe에 따른 분기
     * - persistentExpected=true  -> TTL: 7 days
     * - persistentExpected=false -> TTL: 1 day
     */
    public static void assertRefreshCookiePolicy(String setCookieLine, boolean persistentExpected) {
        assertThat(setCookieLine).contains("HttpOnly");
        assertThat(setCookieLine).contains("Path=/auth");
        assertThat(setCookieLine).contains("SameSite=Lax");

        if (persistentExpected) {
            assertThat(setCookieLine).contains("Max-Age=");
        } else {
            assertThat(setCookieLine).doesNotContain("Max-Age=");
        }
    }

    /**
     * 로그아웃 시 refresh 쿠키 삭제 정책 검증
     * - 보통 Max-Age=0 (즉시 만료) + Expires= (브라우저 호환용)로 내려온다.
     */
    public static void assertRefreshCookieCleared(String setCookieLine) {
        assertThat(setCookieLine).contains("Path=/auth");
        assertThat(setCookieLine).contains("HttpOnly");
        assertThat(setCookieLine).contains("SameSite=Lax");
        assertThat(setCookieLine).contains("Max-Age=0");
        assertThat(setCookieLine).contains("Expires=");
    }
}
