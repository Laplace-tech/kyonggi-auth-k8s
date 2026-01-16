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
import com.kyonggi.backend.auth.identity.signup.support.KyonggiEmailUtils;
import com.kyonggi.backend.auth.support.AuthFlowSupport;
import com.kyonggi.backend.auth.support.AuthHttpSupport;
import com.kyonggi.backend.global.ErrorCode;
import com.kyonggi.backend.infra.TestClockConfig;

/**
 * [Auth][Signup][Service] = SignupService.completeSignup() "서비스 책임" 검증
 *
 * 1) 이메일 도메인(kyonggi) 검증 + normalize
 * 2) 비밀번호 정책 검증 (일치/강도)
 * 3) 닉네임 정책 검증 (정규식)
 * 4) OTP 레코드 조회(락) + verified/만료 검증
 * 5) user 이메일/닉네임 중복 검증
 * 6) user 저장 + OTP 삭제
 */
@DisplayName("[Auth][Signup][Service] completeSignup 통합 테스트")
class AuthSignupServiceIT extends AbstractAuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OtpProperties otpProps;

    @Test
    @DisplayName("completeSignup: 정상 → 2xx + user 생성 + otp 삭제 (실제 OTP 플로우)")
    void complete_signup_success_creates_user_and_deletes_otp() throws Exception {

        String rawEmail = uniqueRawKyonggiEmail("signup_ok");
        String normalizedEmail = KyonggiEmailUtils.normalize(rawEmail);

        // OTP request → 메일로 OTP 받음
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, rawEmail, normalizedEmail);

        // OTP verify → verified
        AuthHttpSupport.performSignupOtpVerify(mvc, normalizedEmail, otp)
                .andExpect(status().is2xxSuccessful());

        // signup complete
        AuthHttpSupport.performSignupComplete(mvc, rawEmail, PASSWORD, PASSWORD, NICKNAME)
                .andExpect(status().is2xxSuccessful());

        // user 생성 및 emailOtp 레코드 삭제 확인
        assertThat(userRepository.findByEmail(normalizedEmail)).isPresent();
        assertThat(emailOtpRepository.findByEmailAndPurpose(normalizedEmail, OtpPurpose.SIGNUP)).isEmpty();
    }

    @Test
    @DisplayName("completeSignup: kyonggi 도메인 아니면 → 400 EMAIL_DOMAIN_NOT_ALLOWED")
    void complete_signup_rejects_non_kyonggi_domain() throws Exception {
        String email = "abc@gmail.com"; // 도메인 검증이 제일 먼저라 OTP/비번/닉 검증 이전에 바로 컷난다

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.EMAIL_DOMAIN_NOT_ALLOWED
        );
    }

    @Test
    @DisplayName("completeSignup: 비밀번호 불일치 → 400 PASSWORD_MISMATCH")
    void complete_signup_password_mismatch() throws Exception {
        String email = uniqueKyonggiEmail("pw_mismatch");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, "DIFF!234", NICKNAME),
                ErrorCode.PASSWORD_MISMATCH
        );
    }

    @Test
    @DisplayName("completeSignup: 약한 비밀번호 → 400 WEAK_PASSWORD")
    void complete_signup_weak_password() throws Exception {
        String email = uniqueKyonggiEmail("weak_pw");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, "1234", "1234", NICKNAME),
                ErrorCode.WEAK_PASSWORD
        );
    }

    @Test
    @DisplayName("completeSignup: 닉네임 형식 오류 → 400 INVALID_NICKNAME")
    void complete_signup_invalid_nickname() throws Exception {
        String email = uniqueKyonggiEmail("badnick");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, PASSWORD, "  !!  "),
                ErrorCode.INVALID_NICKNAME
        );
    }

    @Test
    @DisplayName("completeSignup: OTP 없으면 → 400 OTP_NOT_FOUND")
    void complete_signup_requires_otp_record() throws Exception {
        String email = uniqueKyonggiEmail("nootp");

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.OTP_NOT_FOUND
        );
    }

    @Test
    @DisplayName("completeSignup: OTP verified=false → 400 OTP_NOT_VERIFIED (request만 하고 verify는 안함)")
    void complete_signup_blocks_when_otp_not_verified() throws Exception {
        String email = uniqueKyonggiEmail("not_verified");

        // request 보내서 EmailOtp 레코드만 생성 (not_verified)
        AuthHttpSupport.performSignupOtpRequest(mvc, email)
                .andExpect(status().is2xxSuccessful());

        // DB에 레코드가 생겼는지 확인
        EmailOtp row = emailOtpRepository.findByEmailAndPurpose(email, OtpPurpose.SIGNUP).orElseThrow();
        assertThat(row.isVerified()).isFalse();

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.OTP_NOT_VERIFIED
        );
    }

    @Test
    @DisplayName("completeSignup: OTP 만료 → 400 OTP_EXPIRED (verify 후 Clock 이동)")
    void complete_signup_blocks_when_otp_expired() throws Exception {
        String email = uniqueKyonggiEmail("expired");

        // OTP 발급 → 메일에서 추출
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, email, email);

        // verify 성공 → verified=true
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());

        // TTL 지나게 시간 이동 → expiresAt < now 확정
        TestClockConfig.TEST_CLOCK.advance(
                Duration.ofMinutes(otpProps.ttlMinutes()).plusSeconds(1)
        );

        // complete → OTP_EXPIRED
        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, email, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.OTP_EXPIRED
        );
    }

    @Test
    @DisplayName("completeSignup: 이메일 중복 → 400 EMAIL_ALREADY_EXISTS")
    void complete_signup_email_already_exists() throws Exception {
        String rawEmail = uniqueRawKyonggiEmail("email_dup");
        String email = KyonggiEmailUtils.normalize(rawEmail);

        // OTP 발급
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, rawEmail, email);

        // verify: 검증 성공
        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());

        // 같은 이메일로 유저를 먼저 만들어 둠(중복 상태)
        createUser(email, PASSWORD, NICKNAME);

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, rawEmail, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.EMAIL_ALREADY_EXISTS
        );
    }

    @Test
    @DisplayName("completeSignup: 닉네임 중복 → 400 NICKNAME_ALREADY_EXISTS")
    void complete_signup_nickname_already_exists() throws Exception {
        String rawEmail = uniqueRawKyonggiEmail("nick_dup");
        String email = KyonggiEmailUtils.normalize(rawEmail);

        // OTP 발송 후 검증
        String otp = AuthFlowSupport.requestSignupOtpAndAwaitCode(mvc, rawEmail, email);

        AuthHttpSupport.performSignupOtpVerify(mvc, email, otp)
                .andExpect(status().is2xxSuccessful());

        // 닉네임을 먼저 선점한 유저 생성 (닉네임만 중복 상태 만들기)
        createUser(uniqueKyonggiEmail("someone"), PASSWORD, NICKNAME);

        AuthHttpSupport.expectErrorWithCode(
                AuthHttpSupport.performSignupComplete(mvc, rawEmail, PASSWORD, PASSWORD, NICKNAME),
                ErrorCode.NICKNAME_ALREADY_EXISTS
        );
    }
}