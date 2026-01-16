package com.kyonggi.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * OTP 관련 정책 설정
 * - 보안 정책을 코드에 하드코딩하지 않고 설정으로 분리해서 운영/튜닝 가능하게 한다.
 * 
 * # [Application Domain Config]
 * 
 * otp:
 *   ttl-minutes: 10
 *   max-failures: 5
 *   resend-cooldown-seconds: 20
 *   daily-send-limit: 5
 *   hmac-secret: ${APP_OTP_HMAC_SECRET:?set APP_OTP_HMAC_SECRET (local)}
 */  
@Validated
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        @Min(1) int ttlMinutes,                 // OTP 유효기간
        @Min(1) int maxFailures,                // 허용 실패 횟수
        @Min(1) int resendCooldownSeconds,      // 재전송 쿨다운
        @Min(1) int dailySendLimit,             // 일일 발송제한
        @NotBlank @Size(min = 32) String hmacSecret // OTP 검증/서명(HMAC)용 비밀키
) {
}