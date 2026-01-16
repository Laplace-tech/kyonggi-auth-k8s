package com.kyonggi.backend.auth.identity.signup.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.kyonggi.backend.auth.identity.signup.service.SignupMailSender;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignupOtpMailEventListener {

    private final SignupMailSender mailSender;

    // DB 트랜잭션이 성공적으로 커밋이 된 뒤에만 실행된다. ( 커밋 실패 or 롤백이면 메일이 발송되지 않음 )
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SignupOtpIssuedEvent event) {
        try {
            mailSender.sendOtp(event.email(), event.code());
        } catch (Exception e) {
            // 메일 실패는 "발급 실패"로 취급하지 않는다(발급은 커밋으로 확정됨).
            log.error("회원가입 OTP 메일 발송 실패. email={}", event.email(), e);
        }
    }

}
