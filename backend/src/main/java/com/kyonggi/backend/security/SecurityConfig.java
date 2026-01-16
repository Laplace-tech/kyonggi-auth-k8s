package com.kyonggi.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;

/**
 * Spring Security 전역 보안 설정
 *
 * - JWT 인증: JwtAuthenticationFilter
 * - 인증 필요 리소스 접근 시 인증 없으면: RestAuthEntryPoint (AUTH_REQUIRED)
 * - 토큰은 있는데 invalid면: JwtAuthenticationFilter (ACCESS_INVALID)
 * 
 *  1) @Configuration 
 *  - 이 클래스가 "스프링 설정 클래스"임을 의미
 *  - 내부에 정의된 @Bean 메서드들이 스프링 컨테이너에 등록됨
 * 
 *  2) Spring Security 동작 구조
 *  - 모든 HTTP 요청은 @Controller에 도달하기 전에 "Security Filter Chain"을 먼저 통과함
 *  - 인증/인가 실패 시 @Controller 까지 도달하지 못함.
 * 
 *  3) SecurityFilterChain
 *  - 여러 보안 필터(Authentication, Authorization)의 묶음
 *  - 어떤 요청을 허용/차단할지 이 체인에서 결정
 * 
 * - JwtAuthenticationFilter가 Authorization 헤더를 검사하고 유효하면 SecurityContext에 인증 정보를 세팅한다.
 * - authorizeHttpRequest에서 "인증 필요"인 요청인데 인증이 없으면 EntryPoint가 401 Unauthorized 응답을 내려준다.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    RestAuthEntryPoint restAuthEntryPoint() {
        return new RestAuthEntryPoint(securityErrorWriter);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService, securityErrorWriter);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // CSRF 비활성화 - 지금은 REST API + JWT 방식이고 "세션/쿠키 기반 인증"을 쓰지 않기 때문에 필요 없음.
                .httpBasic(b -> b.disable()) // HTTP Basic 인증 비활성화 - Authorization: Basic ... 방식 사용 안 함
                .formLogin(f -> f.disable()) // formLogin 비활성화 - 스프링 기본 로그인 페이지 사용 안 함

                // 세션 사용 안 함 - 로그인 상태를 서버 세션에 저장하지 않음
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                
                // 인증 실패(= 인증 없이 보호 리소스 접근) 응답 방식 커스터마이즈 - 401 Unauthorized
                .exceptionHandling(eh -> eh.authenticationEntryPoint(restAuthEntryPoint()))

                // JWT 필터 등록: UsernamePasswordAuthenticationFilter 전에 실행되도록 설정
                .addFilterBefore(
                        jwtAuthenticationFilter(),
                        UsernamePasswordAuthenticationFilter.class
                )

                // URL별 접근 정책(인가)
                .authorizeHttpRequests(auth -> auth
                        // 스프링 내부 에러 페이지 접근 허용
                        .requestMatchers("/error").permitAll()

                        // K8s Liveness/Readiness Probe가 인프라 헬스 체크 용도로 엔드포인트를 호출
                        .requestMatchers("/actuator/health/**").permitAll()

                        // 인증 필요 없는 Auth 엔드포인트
                        .requestMatchers("/auth/signup/**").permitAll()
                        .requestMatchers("/auth/login").permitAll()
                        .requestMatchers("/auth/refresh").permitAll()
                        .requestMatchers("/auth/logout").permitAll()

                        // 그 외는 인증 필요 (/auth/me 포함)
                        .anyRequest().authenticated()
                )
                .build(); // SecurityFilterChain 생성
    }
}
