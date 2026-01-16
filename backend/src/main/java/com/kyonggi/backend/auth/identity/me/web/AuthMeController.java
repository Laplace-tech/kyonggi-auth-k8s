package com.kyonggi.backend.auth.identity.me.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kyonggi.backend.auth.identity.me.dto.MeResponse;
import com.kyonggi.backend.auth.identity.me.service.MeService;
import com.kyonggi.backend.security.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * [내 정보 조회 API 컨트롤러]
 * 
 * - JwtAuthenticationFilter가 Access Token을 검증하면 principal(AuthPrincipal)이 주입된다.
 * - 인증이 없으면 principal은 null일 수 있다(설정에 따라 엔트리포인트에서 막히는 게 정석).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthMeController {
    
    private final MeService meService;

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        return meService.me(principal); 
    }

}


