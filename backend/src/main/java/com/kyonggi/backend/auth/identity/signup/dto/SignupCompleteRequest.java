package com.kyonggi.backend.auth.identity.signup.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kyonggi.backend.global.jackson.TrimStringDeserializer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 완료 요청
 * - Controller가 요청 바디를 JSON으로 받을 때 매핑되는(@RequestBody) 입력 전용 객체
 * - 비즈니스 로직 없이 주로 1차 입력 검증(@Valid) 역할
 */
public record SignupCompleteRequest(

        @JsonDeserialize(using = TrimStringDeserializer.class)
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(max = 72, message = "비밀번호가 너무 깁니다.")
        String password,
        
        @NotBlank(message = "비밀번호 확인은 필수입니다.")
        @Size(max = 72, message = "비밀번호 확인이 너무 깁니다.")
        String passwordConfirm,
        
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 50, message = "닉네임이 너무 깁니다.")
        String nickname
        ) {
}



