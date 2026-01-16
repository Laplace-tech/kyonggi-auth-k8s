package com.kyonggi.backend.auth.identity.signup.event;

/**
 * OTP를 발급/저장한 뒤, 커밋 완료 후 메일 발송을 위해 발행하는 이벤트
 * - OTP 원문은 DB에 저장하지 않으므로, 메일 발송은 서비스 계층의 "트랜잭션 커밋 이후"에
 *   이벤트로 처리해야 한다.
 */
public record SignupOtpIssuedEvent (String email, String code) {}
