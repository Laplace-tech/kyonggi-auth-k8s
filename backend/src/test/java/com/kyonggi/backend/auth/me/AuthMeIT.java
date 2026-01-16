package com.kyonggi.backend.auth.me;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.domain.UserRole;
import com.kyonggi.backend.auth.domain.UserStatus;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport.LoginResult;
import com.kyonggi.backend.global.ErrorCode;

/**
 * /auth/me 통합 테스트 (SecurityFilterChain + Controller + Service까지 포함)
 *
 * 실제 흐름:
 *
 * [JwtAuthenticationFilter]
 * - Authorization 헤더 없거나 / "Bearer "로 시작 안 하면: token=null → 그냥 통과
 *   → 이후 SecurityConfig(anyRequest().authenticated())에 걸려 EntryPoint로 401(AUTH_REQUIRED)
 *
 * - Authorization: Bearer <token> 이고 token이 존재하면:
 *   - jwtService.verifyAccessToken(token) 성공 → SecurityContext에 인증 세팅 → 컨트롤러 도달
 *   - 실패(InvalidJwtException) → Filter에서 즉시 401(ACCESS_INVALID)
 *
 * [AuthMeController] -> [MeService]
 * - principal 주입됨(인증 성공한 경우)
 * - DB에서 user 없으면 USER_NOT_FOUND
 * - status != ACTIVE면 ACCOUNT_DISABLED
 * - 정상이면 MeResponse 반환
 */
@DisplayName("[Auth][Me] 내 정보 조회(/auth/me) 통합 테스트")
class AuthMeIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedUser() {
        createDefaultUser();
    }

    // =============================================================
    // 1) 인증 자체가 성립하지 않는 케이스 (EntryPoint -> AUTH_REQUIRED)
    // =============================================================
    @Test
    @DisplayName("me: Authorization 없음 → 401 AUTH_REQUIRED (EntryPoint)")
    void me_requires_auth_when_no_authorization_header() throws Exception {
        ResultActions actions = AuthHttpSupport.performMe(mvc, null);
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("me: Bearer가 아닌 Authorization → 401 AUTH_REQUIRED (EntryPoint)")
    void me_requires_auth_when_non_bearer_header() throws Exception {
        ResultActions actions = AuthHttpSupport.performMe(mvc, "Basic abcdefg");
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("me: Authorization='Bearer ' (토큰 공백) → 401 AUTH_REQUIRED (EntryPoint)")
    void me_requires_auth_when_bearer_token_blank() throws Exception {
        ResultActions actions = AuthHttpSupport.performMe(mvc, "Bearer ");
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.AUTH_REQUIRED);
    }


    // ============================================================
    // 2) 토큰은 있는데 invalid인 케이스 (Filter -> ACCESS_INVALID)
    // ============================================================
    
    @Test
    @DisplayName("me: 형식/서명/issuer/만료 등 검증 실패 JWT → 401 ACCESS_INVALID (Filter)")
    void me_rejects_invalid_jwt() throws Exception {
        // resolveToken()을 통과하려면 "Bearer "로 시작해야 한다.
        ResultActions actions = AuthHttpSupport.performMe(mvc, "Bearer not-a-jwt");
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.ACCESS_INVALID);
    }

    @Test
    @DisplayName("me: refresh 토큰 문자열을 access처럼 사용 → 401 ACCESS_INVALID (Filter)")
    void me_rejects_refresh_token_string_used_as_access_token() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);

        ResultActions actions = AuthHttpSupport.performMe(mvc, AuthHttpSupport.bearer(login.refreshRaw()));
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.ACCESS_INVALID);
    }


    // ============================================================
    // 3) 인증은 성공했지만, 서비스 정책/DB에서 막히는 케이스 (@MeService)
    // ============================================================
    
    @Test
    @DisplayName("me: 토큰은 유효하지만 DB에 유저 없음 → USER_NOT_FOUND")
    void me_returns_user_not_found_when_user_deleted_after_login() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);

        // 토큰 subject(userId)는 그대로인데 DB 레코드만 삭제해서 서비스의 findById를 실패시킨다.
        // 어차피 access token만 보는거라 ㄱㅊ
        Long userId = jdbc.queryForObject(
            "select id from users where email = ?",
            Long.class,
            EMAIL
        );
        jdbc.update("delete from refresh_tokens where user_id = ?", userId); // FK 때문에 refresh_tokens 먼저 삭제
        jdbc.update("delete from users where email = ?", EMAIL);

        ResultActions actions = AuthHttpSupport.performMe(mvc, AuthHttpSupport.bearer(login.accessToken()));
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("me: 토큰은 유효하지만 비활성 계정 → ACCOUNT_DISABLED")
    void me_blocks_when_user_not_active() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);

        UserStatus nonActive = pickNonActiveStatus();
        jdbc.update("update users set status = ? where email = ?", nonActive.name(), EMAIL);

        ResultActions actions = AuthHttpSupport.performMe(mvc, AuthHttpSupport.bearer(login.accessToken()));
        AuthHttpSupport.expectErrorWithCode(actions, ErrorCode.ACCOUNT_DISABLED);
    }

    // ==========================================================
    // 4) 성공 케이스
    // ==========================================================

    @Test
    @DisplayName("me: 유효한 access 토큰 + ACTIVE 사용자 → 200 + 사용자 정보 반환")
    void me_returns_user_info_when_access_token_valid() throws Exception {
        LoginResult login = AuthFlowSupport.loginOk(mvc, EMAIL, PASSWORD, false);

        AuthHttpSupport.performMe(mvc, AuthHttpSupport.bearer(login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.role").value(UserRole.USER.name()))
                .andExpect(jsonPath("$.status").value(UserStatus.ACTIVE.name()));
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
}
