package com.kyonggi.backend.auth.signup;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kyonggi.backend.auth.AbstractAuthIntegrationTest;
import com.kyonggi.backend.auth.config.OtpProperties;
import com.kyonggi.backend.auth.domain.EmailOtp;
import com.kyonggi.backend.auth.domain.OtpPurpose;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.auth.support.MailhogSupport;
import com.kyonggi.backend.global.ErrorCode;
import com.kyonggi.backend.infra.TestClockConfig;

/**
 * SignupOtpService 책임 범위 테스트:
 * -  OTP 발급/검증 정책이 'HTTP 레이어까지 포함해서' 정확히 동작하는지 테스팅
 *
 * =======================================================================
 *              requestSignupOtp(): 발급 + 정책 예외
 * =======================================================================
 * - @DisplayName("request: kyonggi 도메인 아니면 → 400 EMAIL_DOMAIN_NOT_ALLOWED")
 * - @DisplayName("request: 이미 verified + 미만료면 → 400 OTP_ALREADY_VERIFIED")
 * - @DisplayName("request: daily-send-limit 초과 → 429 OTP_DAILY_LIMIT (기본 프로퍼티로)")
 * - @DisplayName("request: 연속 요청(쿨다운 내) → 429 OTP_COOLDOWN")
 * - @DisplayName("request: verified라도 만료된 후면 재발급 가능(2xx)")
 * - @DisplayName("request: 정상 → 2xx + 메일로 OTP 발송됨")
 * 
 * 
 * ========================================================================
 *   verifySignupOtp(): 검증 + 정책 예외 + 멱등 + 실패횟수 커밋(noRollbackFor)
 * ========================================================================
 * - @DisplayName("verify: 정상 → 2xx + verified=true")
 * - @DisplayName("verify: 요청 이력 없으면 → 400 OTP_NOT_FOUND")
 * - @DisplayName("verify: 이미 verified면 멱등 성공(2xx) + 실패횟수 증가 없음")
 * - @DisplayName("verify: 만료된 OTP → 400 OTP_EXPIRED")
 * - @DisplayName("verify: 실패횟수 초과(>= maxFailures) → 400 OTP_TOO_MANY_FAILURES")
 * - @DisplayName("verify: 코드 불일치 → 400 OTP_INVALID + failedAttempts가 DB에 +1 커밋됨(noRollbackFor 검증)")
 * 
 * [AuthHttpSupport]
 * - performSignupOtpRequest = POST: /auth/signup/otp/request
 * - performSignupOtpVerify = POST: /auth/signup/otp/verify
 */
@DisplayName("[Auth][Signup][OTP-Service] request/verify 통합 테스트")
class AuthSignupOtpServiceIT extends AbstractAuthIntegrationTest {

    /**
     * MockMVC:
     * - 진짜 HTTP 서버 없이도 요청/응답을 시뮬레이션하는 테스트 클라이언트
     * - 필터체인/예외처리/컨트롤러까지 "웹 레이어"를 그대로 통과한다.
     */
    @Autowired MockMvc mvc;
    @Autowired OtpProperties otpProps;

    
    @Test
    @DisplayName("request: kyonggi 도메인 아니면 → 400 EMAIL_DOMAIN_NOT_ALLOWED")
    void request_rejects_non_kyonggi_domain() throws Exception {
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpRequest(mvc, "abc@gmail.com"),
                ErrorCode.EMAIL_DOMAIN_NOT_ALLOWED
        );
    }

    @Test
    @DisplayName("request: 이미 verified + 미만료면 → 400 OTP_ALREADY_VERIFIED")
    void request_blocks_when_already_verified_and_not_expired() throws Exception {
        String email = uniqueKyonggiEmail("verified");

        // OTP 요청 및 추출
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);
        
        // verify 성공 → verified=true
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());
                
        // TTL 안 지난 상태에서 재요청 → OTP_ALREADY_VERIFIED
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpRequest(mvc, email),
                ErrorCode.OTP_ALREADY_VERIFIED
        );
    }

    @Test
    @DisplayName("request: daily-send-limit 초과 → 429 OTP_DAILY_LIMIT (기본 프로퍼티로)")
    void request_blocks_by_daily_limit_using_default_props() throws Exception {
        String email = uniqueKyonggiEmail("daily");

        int limit = otpProps.dailySendLimit();
        int cooldownSeconds = otpProps.resendCooldownSeconds();

        // limit번까지는 성공 (쿨다운은 clock으로 넘겨서 우회)
        for (int i = 0; i < limit; i++) {
            AuthHttpSupport.performSignupOtpRequest(mvc, email)
                    .andExpect(status().is2xxSuccessful());

            // (쿨다운 + 1초) 후로 시간 조정
            TestClockConfig.TEST_CLOCK.advance(Duration.ofSeconds(cooldownSeconds).plusSeconds(1));
        }

        // limit+1번째는 daily limit
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpRequest(mvc, email),
                ErrorCode.OTP_DAILY_LIMIT
        );
    }

    @Test
    @DisplayName("request: 연속 요청(쿨다운 내) → 429 OTP_COOLDOWN")
    void request_blocks_by_cooldown() throws Exception {
        String email = uniqueKyonggiEmail("cooldown");

        AuthHttpSupport.performSignupOtpRequest(mvc, email)
                .andExpect(status().is2xxSuccessful());

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpRequest(mvc, email),
                ErrorCode.OTP_COOLDOWN
        );
    }
    @Test
    @DisplayName("request: verified라도 만료된 후면 재발급 가능(2xx)")
    void request_allows_reissue_when_verified_but_expired() throws Exception {
        String email = uniqueKyonggiEmail("verified_expired");

        // 최초 발급 및 OTP 추출
        String otp1 = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);
        
        // verify로 verified 상태 만들기
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp1)
                .andExpect(status().is2xxSuccessful());

        // TTL 지나게 시간 이동(만료)
        TestClockConfig.TEST_CLOCK.advance(Duration.ofMinutes(otpProps.ttlMinutes()).plusSeconds(1));

        // Mailhog 비우고 재발급(reissue) 요청: (TTL 지나서 가능)
        MailhogSupport.clearAll(); 
        String otp2 = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);

        assertThat(otp2).isNotEqualTo(otp1); // otp2 != otp1
    }


    @Test
    @DisplayName("request: 정상 → 2xx + 메일로 OTP 발송됨")
    void request_success_sends_otp_mail() throws Exception {
        String email = uniqueKyonggiEmail("request_ok");
        AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);
    }




    @Test
    @DisplayName("verify: 정상 → 2xx + verified=true")
    void verify_success_marks_verified() throws Exception {
        String email = uniqueKyonggiEmail("verify_ok");

        // OTP 요청 및 추출
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);

        // verify로 인증: verified 상태 만들기
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());
 
        // 해당 EmailOtp 레코드를 찾아서 검증되었는지(Verified) 확인
        EmailOtp row = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow();
        assertThat(row.isVerified()).isTrue();
    }

    @Test
    @DisplayName("verify: 요청 이력 없으면 → 400 OTP_NOT_FOUND")
    void verify_without_request_returns_not_found() throws Exception {
        String email = uniqueKyonggiEmail("nohist");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpVerify(mvc, email, "123456"),
                ErrorCode.OTP_NOT_FOUND
        );
    }

    @Test
    @DisplayName("verify: 이미 verified면 멱등 성공(2xx) + 실패횟수 증가 없음")
    void verify_is_idempotent_when_already_verified() throws Exception {
        String email = uniqueKyonggiEmail("idem");

        // OTP 발송 및 추출
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);
        
        // 첫 verify 성공
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());

        // "failedAttempts == 0"
        EmailOtp before = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow();
        int failuresBefore = before.getFailedAttempts();

        /**
         * (멱등) 두 번째 verify: 
         * - 코드가 틀려도(또는 뭐가 와도) verified면 return이라 성공
         * - "failedAttempts == 0" 으로 변화 없음
         */
        AuthHttpSupport.performSignupOtpVerify(mvc, email, "000000")
                .andExpect(status().is2xxSuccessful());

        EmailOtp after = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow();
        assertThat(after.isVerified()).isTrue();
        assertThat(after.getFailedAttempts()).isEqualTo(failuresBefore);
    }


    @Test
    @DisplayName("verify: 만료된 OTP → 400 OTP_EXPIRED")
    void verify_expired_returns_otp_expired() throws Exception {
        String email = uniqueKyonggiEmail("expired");

        // OTP 발송
        AuthHttpSupport.performSignupOtpRequest(mvc, email)
                .andExpect(status().is2xxSuccessful());

        // TTL(분) 만큼 넘겨서 만료시키고 verify 시도 => 만료됨
        TestClockConfig.TEST_CLOCK.advance(Duration.ofMinutes(otpProps.ttlMinutes()).plusSeconds(1));

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpVerify(mvc, email, "123456"),
                ErrorCode.OTP_EXPIRED
        );
    }

    @Test
    @DisplayName("verify: 실패횟수 초과(>= maxFailures) → 400 OTP_TOO_MANY_FAILURES")
    void verify_too_many_failures_blocks() throws Exception {
        String email = uniqueKyonggiEmail("manyfail");

        // OTP 발송
        AuthHttpSupport.performSignupOtpRequest(mvc, email)
                .andExpect(status().is2xxSuccessful());

        int max = otpProps.maxFailures(); // 설정값에서 "최대 실패 횟수" 추출

        // max번까지는 OTP_INVALID로 실패 누적
        for (int i = 0; i < max; i++) {
            AuthHttpSupport.expectErrorWithCode(
                    AuthHttpSupport.performSignupOtpVerify(mvc, email, "000000"),
                    ErrorCode.OTP_INVALID
            );
        }

        // 그 다음은 too many failures
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpVerify(mvc, email, "000000"),
                ErrorCode.OTP_TOO_MANY_FAILURES
        );
    }

    @Test
    @DisplayName("verify: 코드 불일치 → 400 OTP_INVALID + failedAttempts가 DB에 +1 커밋됨(noRollbackFor 검증)")
    void verify_wrong_code_increments_failed_attempts() throws Exception {
        String email = uniqueKyonggiEmail("wrongcode");

        // OTP 레코드 생성
        AuthHttpSupport.performSignupOtpRequest(mvc, email)
                .andExpect(status().is2xxSuccessful());

        // EmailOtp 레코드 꺼내서 실패 횟수(EmailOtp.failedAttempts) 추출
        EmailOtp before = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow();
        int failuresBefore = before.getFailedAttempts();

        // 틀린 코드 → OTP_INVALID
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupOtpVerify(mvc, email, "000000"),
                ErrorCode.OTP_INVALID
        );

        // ✅ noRollbackFor 덕분에 실패횟수가 실제로 커밋되어 +1 되어야 함 (failuresBefore + 1)
        EmailOtp after = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow(); 

        // 미인증 상태 유지 + 실패 횟수 1만큼 증가
        assertThat(after.isVerified()).isFalse();
        assertThat(after.getFailedAttempts()).isEqualTo(failuresBefore + 1);
    }
    

}
