package com.kyonggi.backend.auth.identity.signup.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 회원가입 OTP 검증 요청
 * - 이메일 형식 + 코드는 6자리 숫자인지만 1차 검증
 */
public record SignupOtpVerifyRequest(
        @NotBlank @Email
        String email,
        
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "6자리 숫자만 허용")
        String code
) {}

