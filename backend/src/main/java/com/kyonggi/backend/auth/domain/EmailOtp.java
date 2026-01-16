package com.kyonggi.backend.auth.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;


/**
 * 이메일 OTP 인증 상태 엔티티
 * - @Entity: 이 클래스가 DB 테이블과 매핑되는 JPA 엔티티임을 의미
 * 
 * (email, purpose) 유니크 제약
 * - 같은 이메일 + 같은 목적(SIGNUP)에 대해 OTP는 항상 하나만 존재
 * - 중복 발급 / 레이스 컨디션 방지
 */
@Entity
@Table(name = "email_otp", 
       uniqueConstraints = @UniqueConstraint(name = "uq_email_otp_email_purpose", 
                                            columnNames = {"email", "purpose" }))
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class EmailOtp {

    // PK - AUTO_INCREMENT
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email; // 인증 대상 이메일

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash; // OTP 코드 원문이 아닌 해시값 (서버만 검증 가능)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OtpPurpose purpose; // OTP 목적: SIGNUP, PASSWORD_RESET 등

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt; // OTP 만료 시각

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt; // 인증 완료 시각 - null이면 아직 인증되지 않음

    @Column(name = "failed_attempts", nullable = false) // 
    private int failedAttempts; // 인증 실패 횟수

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt; // 마지막 OTP 발송 시각

    @Column(name = "resend_available_at", nullable = false)
    private LocalDateTime resendAvailableAt; // 재전송 가능 시각 (쿨다운)

    @Column(name = "send_count_date", nullable = false)
    private LocalDate sendCountDate; // 일일 발송 횟수 관리용 날짜

    @Column(name = "send_count", nullable = false)
    private int sendCount; // 해당 날짜의 발송 횟수

    public static EmailOtp create(String email, String codeHash, OtpPurpose purpose,
            LocalDateTime expiresAt, LocalDateTime now, LocalDate today,
            LocalDateTime resendAvailableAt) {
        EmailOtp o = new EmailOtp();
        o.email = email; // 클라이언트가 보낸 rawEmail
        o.codeHash = codeHash; // 6자리 인증 숫자
        o.purpose = purpose; // SIGNUP
        o.expiresAt = expiresAt; // now.plusMinutes
        o.verifiedAt = null;
        o.failedAttempts = 0;
        o.lastSentAt = now; // now
        o.resendAvailableAt = resendAvailableAt; // 쿨다운
        o.sendCountDate = today;
        o.sendCount = 1;
        return o;
    }

    public void reissue(String newCodeHash, LocalDateTime newExpiresAt, LocalDateTime now,
            LocalDate today, LocalDateTime resendAvailableAt) {
        this.codeHash = newCodeHash;
        this.expiresAt = newExpiresAt;
        this.verifiedAt = null;
        this.failedAttempts = 0;

        if (!this.sendCountDate.equals(today)) {
            this.sendCountDate = today;
            this.sendCount = 0;
        }

        this.sendCount += 1;
        this.lastSentAt = now;
        this.resendAvailableAt = resendAvailableAt;
    }

    // OTP 만료 여부 판단
    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    // 인증 완료 여부
    public boolean isVerified() {
        return verifiedAt != null;
    }

    // 인증 성공 처리
    public void markVerified(LocalDateTime now) {
        this.verifiedAt = now;
    }

    // 인증 실패 횟수 증가
    public void increaseFailure() {
        this.failedAttempts += 1;
    }

    // getters
    public String getEmail() {return email;}
    public String getCodeHash() {return codeHash;}
    public OtpPurpose getPurpose() {return purpose;}
    public LocalDateTime getExpiresAt() {return expiresAt;}
    public LocalDateTime getVerifiedAt() {return verifiedAt;}
    public int getFailedAttempts() {return failedAttempts;}
    public LocalDateTime getResendAvailableAt() {return resendAvailableAt;}
    public LocalDate getSendCountDate() {return sendCountDate;}
    public int getSendCount() {return sendCount;}
}
