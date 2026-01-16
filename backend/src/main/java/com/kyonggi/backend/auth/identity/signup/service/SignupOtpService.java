package com.kyonggi.backend.auth.identity.signup.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kyonggi.backend.auth.config.OtpProperties;
import com.kyonggi.backend.auth.domain.EmailOtp;
import com.kyonggi.backend.auth.domain.OtpPurpose;
import com.kyonggi.backend.auth.identity.signup.event.SignupOtpIssuedEvent;
import com.kyonggi.backend.auth.identity.signup.support.KyonggiEmailUtils;
import com.kyonggi.backend.auth.identity.signup.support.OtpCodeGenerator;
import com.kyonggi.backend.auth.identity.signup.support.OtpHasher;
import com.kyonggi.backend.auth.repo.EmailOtpRepository;
import com.kyonggi.backend.global.ApiException;
import com.kyonggi.backend.global.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 회원가입 OTP 정책 서비스
 * 
 * 1) OTP 발급: public void requestSignupOtp(String rawEmail) {...}
 *  - 도메인 검증 / 정규화
 *  - 해당 이메일 상태 검사 (쿨다운 / 일일 제한 / 검증 완료)
 *  - OTP는 보안을 위해 해시만 DB에 저장, 원문은 SignupMailSender가 커밋 이후 이벤트로 메일 전송
 * 
 * 2) OTP 검증: public void verifySignupOtp(String rawEmail, String incomingCode) {...}
 *  - 실패 횟수는 반드시 누적되어야 하므로, "OTP 코드 불일치"는 롤백하지 않는다. (해당 레코드의 속성 값이 증가해야함)
 */
@Service
@RequiredArgsConstructor
public class SignupOtpService {

    private static final OtpPurpose PURPOSE = OtpPurpose.SIGNUP;

    private final EmailOtpRepository emailOtpRepository;
    private final ApplicationEventPublisher eventPublisher; // 메일 발송을 "커밋 이후"로 보내기 위한 이벤트 발행자

    private final OtpCodeGenerator otpCodeGenerator;
    private final OtpHasher otpHasher;
    private final OtpProperties props;
    private final Clock clock;

    @Transactional
    public void requestSignupOtp(String rawEmail) {
        String email = normalizeKyonggiEmail(rawEmail); // @DisplayName("request: kyonggi 도메인 아니면 → 400 EMAIL_DOMAIN_NOT_ALLOWED")

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        /**
         * [동시성 정책 보장 메커니즘]
         * 
         * 1) row가 이미 존재할 때:
         *  - findByEmailAndPurposeForUpdate(...): SELECT ... FOR UPDATE
         *  - 같은 (email, purpose) 요청이 동시에 와도 1개의 트랜잭션만 진행하도 나머지는 대기함
         *  - 쿨다운/일일제한/verified 같은 정책 체크가 "정확히" 적용된다.
         * 
         * 2) row가 없을 때(최초 요청)
         *  - 잠금 row 자체가 없어서 둘이 동시에 INSERT 경쟁이 날 수 있다.
         *  - 그래서 DB에 (email,purpose) UNIQUE 제약이 “최후의 단일 승자”를 만든다.
         *  - 패자는 DataIntegrityViolationException을 받고, 다시 잠금 조회 후 동일 정책(validateReissuePolicy)을 적용한다.
         */
        EmailOtp otp = emailOtpRepository.findByEmailAndPurposeForUpdate(email, PURPOSE).orElse(null);
        if (otp != null) {
            // @DisplayName("request: 이미 verified + 미만료면 → 400 OTP_ALREADY_VERIFIED") 
            // @DisplayName("request: daily-send-limit 초과 → 429 OTP_DAILY_LIMIT (기본 프로퍼티로)")
            // @DisplayName("request: 연속 요청(쿨다운 내) → 429 OTP_COOLDOWN")
            validateReissuePolicy(otp, now, today);
        }

        String code = otpCodeGenerator.generate6Digits();
        String codeHash = otpHasher.hash(code);

        LocalDateTime expiresAt = now.plusMinutes(props.ttlMinutes());
        LocalDateTime resendAvailableAt = now.plusSeconds(props.resendCooldownSeconds());

        // EmailOtp 엔티티 생성
        EmailOtp toSave = (otp == null)
                ? EmailOtp.create(email, codeHash, PURPOSE, expiresAt, now, today, resendAvailableAt)
                : reissueAndReturn(otp, codeHash, expiresAt, now, today, resendAvailableAt);

        try {
            // @DisplayName("request: 정상 → 2xx + 메일로 OTP 발송됨")
            // @DisplayName("request: verified라도 만료된 후면 재발급 가능(2xx)")
            emailOtpRepository.save(toSave);
        } catch (DataIntegrityViolationException e) {
            // 레이스로 누가 먼저 생성한 경우: 다시 잠금 조회 후 정책대로 처리/재발급
            EmailOtp existing = emailOtpRepository.findByEmailAndPurposeForUpdate(email, PURPOSE)
                    .orElseThrow(() -> e);

            validateReissuePolicy(existing, now, today);
            reissueAndReturn(existing, codeHash, expiresAt, now, today, resendAvailableAt);
            emailOtpRepository.save(existing);
        }

        /**
         * 메일은 트랜잭션 커밋 후, SignupMailSender가 처리한다.
         * - @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
         */
        eventPublisher.publishEvent(new SignupOtpIssuedEvent(email, code));
    }

    @Transactional(noRollbackFor = OtpInvalidException.class)
    public void verifySignupOtp(String rawEmail, String incomingCode) {
        String email = normalizeKyonggiEmail(rawEmail);
        LocalDateTime now = LocalDateTime.now(clock);

        /**
         * findByEmailAndPurposeForUpdate: PESSIMISTIC_WRITE 락 걸린 상태로 조회
         * - 비관적 락: 다른 트랜잭션이 같은 행을 수정하지 못하게 막는다. 
         * - 동시 검증 요청이 실패 횟수 누적을 뚫지 못하게 한다.
         * 
         * 영속성 컨텍스트에 올라온 엔티티이므로, 이후의 상태 변경은 "더티체킹"으로 커밋 시점에 DB에 반영된다.
         * - verified 처리
         * - 실패 횟수 증가
         */
        EmailOtp otpEntity = emailOtpRepository.findByEmailAndPurposeForUpdate(email, PURPOSE)
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_NOT_FOUND)); // @DisplayName("verify: 요청 이력 없으면 → 400 OTP_NOT_FOUND")

         // 이미 검증 완료면 멱등 성공(실패 횟수 증가 없음)
        if (otpEntity.isVerified()) { // @DisplayName("verify: 이미 verified면 멱등 성공(2xx) + 실패횟수 증가 없음")
            return;
        }

        if (otpEntity.isExpired(now)) {
            throw new ApiException(ErrorCode.OTP_EXPIRED); // @DisplayName("verify: 만료된 OTP → 400 OTP_EXPIRED")
        }

        if (otpEntity.getFailedAttempts() >= props.maxFailures()) {
            throw new ApiException(ErrorCode.OTP_TOO_MANY_FAILURES); // @DisplayName("verify: 실패횟수 초과(>= maxFailures) → 400 OTP_TOO_MANY_FAILURES")
        }


        /**
         * 불일치 실패: 실패 횟수 +1 후 "OTP_INVALID": (noRollbackFor로 커밋 보장)
         * - 정책상 실패 횟수를 반드시 남겨야 하므로 예외가 터져도 롤백하지 않는다.
         */
        if (!otpHasher.matches(incomingCode, otpEntity.getCodeHash())) { // @DisplayName("verify: 코드 불일치 → 400 OTP_INVALID + failedAttempts가 DB에 +1 커밋됨(noRollbackFor 검증)")
            otpEntity.increaseFailure();    
            throw new OtpInvalidException(); 
        }

        otpEntity.markVerified(now); // @DisplayName("verify: 정상 → 2xx + verified=true")
    }


    
    private void validateReissuePolicy(EmailOtp otp, LocalDateTime now, LocalDate today) {
        // 이미 검증 + 미만료면 재요청 금지
        if (otp.isVerified() && !otp.isExpired(now)) {
            throw new ApiException(ErrorCode.OTP_ALREADY_VERIFIED);
        }

        // 일일 제한: 날짜가 바뀌면 카운트는 0으로 취급
        int currentCount = otp.getSendCountDate().equals(today) ? otp.getSendCount() : 0;
        if (currentCount >= props.dailySendLimit()) {
            throw new ApiException(ErrorCode.OTP_DAILY_LIMIT);
        }

        // 쿨다운
        if (otp.getResendAvailableAt().isAfter(now)) {
            long retry = Duration.between(now, otp.getResendAvailableAt()).getSeconds();
            throw new ApiException(
                    ErrorCode.OTP_COOLDOWN,
                    ErrorCode.OTP_COOLDOWN.defaultMessage(),
                    (int) Math.max(retry, 1),
                    null
            );
        }
    }

    private EmailOtp reissueAndReturn(
            EmailOtp otpEntity,
            String codeHash,
            LocalDateTime expiresAt,
            LocalDateTime now,
            LocalDate today,
            LocalDateTime resendAvailableAt) {
        otpEntity.reissue(codeHash, expiresAt, now, today, resendAvailableAt);
        return otpEntity;
    }

    private static class OtpInvalidException extends ApiException {
        public OtpInvalidException() {
            super(ErrorCode.OTP_INVALID);
        }
    }

    private String normalizeKyonggiEmail(String rawEmail) {
        KyonggiEmailUtils.validateKyonggiDomain(rawEmail);
        return KyonggiEmailUtils.normalize(rawEmail);
    }
}
