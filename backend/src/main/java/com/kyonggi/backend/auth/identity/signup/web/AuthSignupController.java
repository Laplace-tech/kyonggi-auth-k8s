package com.kyonggi.backend.auth.identity.signup.web;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kyonggi.backend.auth.identity.signup.dto.SignupCompleteRequest;
import com.kyonggi.backend.auth.identity.signup.dto.SignupOtpRequest;
import com.kyonggi.backend.auth.identity.signup.dto.SignupOtpVerifyRequest;
import com.kyonggi.backend.auth.identity.signup.service.SignupOtpService;
import com.kyonggi.backend.auth.identity.signup.service.SignupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 회원가입 API
 * - 요청 검증(@Valid) + 서비스 호출만 담당
 */

@RestController
@RequestMapping("/auth/signup")
@RequiredArgsConstructor
public class AuthSignupController {

    private final SignupOtpService otpService;
    private final SignupService signupService;

    // OTP 발급 요청: 204 No Content
    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@RequestBody @Valid SignupOtpRequest req) {
        otpService.requestSignupOtp(req.email());
        return ResponseEntity.noContent().build();
    }

    // OTP 검증: 204 No Content
    @PostMapping("/otp/verify")
    public ResponseEntity<Void> verifyOtp(@RequestBody @Valid SignupOtpVerifyRequest req) {
        otpService.verifySignupOtp(req.email(), req.code());
        return ResponseEntity.noContent().build();
    }

    // 회원가입 완료: 201 Created
    @PostMapping("/complete")
    public ResponseEntity<Void> complete(@RequestBody @Valid SignupCompleteRequest req) {
        signupService.completeSignup(
            req.email(), 
            req.password(), 
            req.passwordConfirm(), 
            req.nickname()
        ); 
        return ResponseEntity.status(201).build();
    }
}


