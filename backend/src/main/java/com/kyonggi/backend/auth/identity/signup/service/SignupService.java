package com.kyonggi.backend.auth.identity.signup.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;  

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kyonggi.backend.auth.domain.EmailOtp;
import com.kyonggi.backend.auth.domain.OtpPurpose;
import com.kyonggi.backend.auth.domain.User;
import com.kyonggi.backend.auth.identity.signup.support.KyonggiEmailUtils;
import com.kyonggi.backend.auth.identity.signup.support.SignupPatterns;
import com.kyonggi.backend.auth.repo.EmailOtpRepository;
import com.kyonggi.backend.auth.repo.UserRepository;
import com.kyonggi.backend.global.ApiException;
import com.kyonggi.backend.global.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final EmailOtpRepository emailOtpRepository;
    private final UserRepository userRepository;

    private final Clock clock;
    private final PasswordEncoder passwordEncoder;

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(SignupPatterns.PASSWORD_REGEX);
    private static final Pattern NICKNAME_PATTERN = Pattern.compile(SignupPatterns.NICKNAME_REGEX);

    @Transactional
    public void completeSignup(String rawEmail, String rawPassword, String rawPasswordConfirm, String nickname) {
        String email = normalizeKyonggiEmail(rawEmail); // @DisplayName("completeSignup: kyonggi 도메인 아니면 → 400 EMAIL_DOMAIN_NOT_ALLOWED")
        LocalDateTime now = LocalDateTime.now(clock);

        /**
         * @DisplayName("completeSignup: 비밀번호 불일치 → 400 PASSWORD_MISMATCH")
         * @DisplayName("completeSignup: 약한 비밀번호 → 400 WEAK_PASSWORD")
         * @DisplayName("completeSignup: 닉네임 형식 오류 → 400 INVALID_NICKNAME")
         */
        validatePassword(rawPassword, rawPasswordConfirm);
        String nick = normalizeAndValidateNickname(nickname);

        /**
         * @Lock(PESSIMISTIC_WRITE): 비관적 락
         * - 동시에 두 트랜잭션이 같은 OTP 레코드를 사용하려 할 때,
         *   먼저 락을 획득한 트랜잭션이 끝날 때까지 다른 트랜잭션이 대기하도록 함.
         * 
         * - OTP는 잠금 조회: 동시에 complete가 두 번 들어와도 정책이 흔들리지 않게 한다.
         * - 해당 이메일이 OTP 레코드에 있는지 검사 -> 없으면 OTP 인증 필요
         */
        EmailOtp otp = emailOtpRepository.findByEmailAndPurposeForUpdate(email, OtpPurpose.SIGNUP) // @DisplayName("completeSignup: OTP 없으면 → 400 OTP_NOT_FOUND")
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_NOT_FOUND));

        // OTP 인증 미완료
        if (!otp.isVerified()) 
            throw new ApiException(ErrorCode.OTP_NOT_VERIFIED); // @DisplayName("completeSignup: OTP verified=false → 400 OTP_NOT_VERIFIED (request만 하고 verify는 안함)")

        // OTP 인증 만료, 재인증 필요 (EmailOtp: reissue)
        if (otp.isExpired(now)) 
            throw new ApiException(ErrorCode.OTP_EXPIRED); // @DisplayName("completeSignup: OTP 만료 → 400 OTP_EXPIRED (verify 후 Clock 이동)")
        
        
        /// 중복 선검사 + 최종은 DB 제약으로 차단
        if (userRepository.existsByEmail(email)) 
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS); // @DisplayName("completeSignup: 이메일 중복 → 400 EMAIL_ALREADY_EXISTS")
        
        if (userRepository.existsByNickname(nick))
            throw new ApiException(ErrorCode.NICKNAME_ALREADY_EXISTS); // @DisplayName("completeSignup: 닉네임 중복 → 400 NICKNAME_ALREADY_EXISTS")
        

        String passwordHash = passwordEncoder.encode(rawPassword);

        try {
            userRepository.save(User.create(email, passwordHash, nick));
        } catch (DataIntegrityViolationException e) {
            /**
             * 레이스로 중복 가입 시도된 경우에도 DB 무결성 제약으로 최종 차단
             * - 이메일/닉네임 둘 다 Unique 제약이 걸려 있으므로, (uq_users_email, uq_users_nickname)
             *    어떤 제약에 걸렸는지 다시 조회하여 적절한 에러코드로 매핑한다.
             * - 정말 다른 무결성 문제면 그대로 예외를 던진다. throw e;
             */
            if (userRepository.existsByEmail(email)) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
            }
            if (userRepository.existsByNickname(nick)) {
                throw new ApiException(ErrorCode.NICKNAME_ALREADY_EXISTS);
            }
            throw e;
        }

        // 재사용 방지: OTP 레코드 제거
        emailOtpRepository.delete(otp); // @DisplayName("completeSignup: 정상 → 2xx + user 생성 + otp 삭제 (실제 OTP 플로우)")
    }


    private String normalizeKyonggiEmail(String rawEmail) {
        KyonggiEmailUtils.validateKyonggiDomain(rawEmail);
        return KyonggiEmailUtils.normalize(rawEmail);
    }

    private void validatePassword(String rawPassword, String rawPasswordConfirm) {
        if (rawPassword == null || rawPasswordConfirm == null || !rawPassword.equals(rawPasswordConfirm)) {
            throw new ApiException(ErrorCode.PASSWORD_MISMATCH);
        }
        if (!PASSWORD_PATTERN.matcher(rawPassword).matches()) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD);
        }
    }

    private String normalizeAndValidateNickname(String nickname) {
        String nick = nickname == null ? "" : nickname.trim();
        if (!NICKNAME_PATTERN.matcher(nick).matches()) {
            throw new ApiException(ErrorCode.INVALID_NICKNAME);
        }
        return nick;
    }
}
