package com.kyonggi.backend.auth.identity.signup.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kyonggi.backend.global.jackson.TrimStringDeserializer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 회원가입 OTP 발송 요청
 * - 이메일 "형식"까지만 1차 검증
 * - 경기대 도메인 정책은 서비스에서 검증
 */
public record SignupOtpRequest(
        @JsonDeserialize(using = TrimStringDeserializer.class)
        @NotBlank 
        @Email String email
) {};
