package com.kyonggi.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


/*
  @ConfigurationProperties(prefix = "app.auth"):
  application.yml + application-local.yml 설정 파일의 app.auth.* 값을 타입 안정성 있게 바인딩해준다.
  
  # [Application Domain Config]
  
  app:
    auth:
      jwt:
        issuer: kyonggi-board-local
        access-ttl-seconds: 900
        secret: ${APP_AUTH_JWT_SECRET:?set APP_AUTH_JWT_SECRET (local)}

      refresh:
        cookie-name: KG_REFRESH
        cookie-path: /auth    
        cookie-same-site: Lax  
        cookie-secure: false # 로컬은 HTTPS가 아니므로 false
        remember-me-seconds: 604800 
        session-ttl-seconds: 86400 
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(@Valid @NotNull Jwt jwt, 
                             @Valid @NotNull Refresh refresh) {
    
    /**
     * Access Token(JWT) 관련 설정
     * - issuer: 토큰 발급자 식별자
     * - accessTtlSeconds: Access Token 수명
     * - secret: HS256 서명을 위한 비밀키 문자열
     */
    public record Jwt(
        @NotBlank String issuer,
        @Min(1) long accessTtlSeconds, 
        @NotBlank @Size(min = 32) String secret
    ) {}


    /**
     * Refresh Token + 쿠키 관련 설정
     * - cookieName: Refresh 토큰을 담을 쿠키 이름 (KG_REFRESH)
     * - cookiePath: 이 경로에 해당하는 요청에만 쿠키를 같이 전송 (/auth)
     * - cookieSameSite: SameSite 정책 (Lax / Strict / None)
     * - cookieSecure: https 에서만 전송 여부 (운영에선 true)
     * - rememberMeSeconds: rememberMe=true 일 때 서버 측 세션 TTL
     * - sessionTtlSeconds: rememberMe=false 일 때 서버 측 세션 TTL
     */
    public record Refresh(
            @NotBlank String cookieName,

            // 최소 형식만 강제: "/"로 시작 (오타로 "auth" 같은 값 들어오는 것 방지)
            @NotBlank @Pattern(regexp = "^/.*", message = "cookiePath must start with '/'")
            String cookiePath,

            @NotNull SameSite cookieSameSite,

            boolean cookieSecure,

            @Min(1) long rememberMeSeconds,

            @Min(1) long sessionTtlSeconds  
    ) {}

    // SameSite는 오타가 치명적이라 enum으로 고정
    public enum SameSite {
        Lax, Strict, None
    }
}
