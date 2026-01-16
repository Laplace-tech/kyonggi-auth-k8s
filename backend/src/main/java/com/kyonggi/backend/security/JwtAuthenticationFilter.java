package com.kyonggi.backend.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
 
import com.kyonggi.backend.global.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * "Security Filter Chain"에서 "JWT 기반 인증"을 수행하는 인증 필터
 * 
 * 역할:
 * - 모든 요청에서 Authorization 헤더에 Bearer 토큰이 있으면 Access Token을 꺼낸다.
 * - JwtService로 JWT 서명/만료/issuer를 검증해서 AuthPrincipal(userId, role)을 얻는다.
 * - 검증이 성공하면 SecurityContext에 Authentication을 세팅한다.
 * 
 * 정책:
 * - 토큰이 "없으면" 통과한다. (차단은 SecurityConfig의 인가 규칙 + EntryPoint가 담당)
 * - 토큰이 "있는데 유효하지 않으면" 여기서 401(ApiError 포맷)로 종료한다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 이미 인증이 만들어진 요청이면 중복 처리하지 않는다.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        /**
         * @DisplayName("me: Authorization 없음 → 401 AUTH_REQUIRED (EntryPoint)")
         * @DisplayName("me: Bearer가 아닌 Authorization → 401 AUTH_REQUIRED (EntryPoint)")
         * @DisplayName("me: Authorization='Bearer ' (토큰 공백) → 401 AUTH_REQUIRED (EntryPoint)")
         */

        /**
         * @DisplayName("me: Authorization 없음 → 401 AUTH_REQUIRED (EntryPoint)")
         * @DisplayName("me: Bearer가 아닌 Authorization → 401 AUTH_REQUIRED (EntryPoint)")
         * @DisplayName("me: Authorization='Bearer ' (토큰 공백) → 401 AUTH_REQUIRED (EntryPoint)")
         * --- 책임: 이 필터 아님 ---
         * Authorization: Bearer <ACCESS_JWT> 에서 Access Token(JWT) 추출
         * "없음"은 통과. 보호 자원 여부 판단은 SecurityConfig/EntryPoint에서 한다.
         */
        String token = resolveBearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            /**
             * @DisplayName("me: 형식/서명/issuer/만료 등 검증 실패 JWT → 401 ACCESS_INVALID (Filter)")
             * @DisplayName("me: refresh 토큰 문자열을 access처럼 사용 → 401 ACCESS_INVALID (Filter)")
             * --- 책임: JwtService ---
             * JWT 검증 + AuthPrincipal(userId, role) 추출
             * - jwtService.verifyAccessToken() 예외: InvalidJwtException
             */
            AuthPrincipal principal = jwtService.verifyAccessToken(token);

            // 권한(ROLE_*) 세팅: ROLE_USER  
            var authorities = List.of(new SimpleGrantedAuthority(principal.authority()));

            // Spring Security가 이해하는 Authentication 생성 
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    authorities
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // SecurityContext에 인증 정보 저장
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 다음 필터/컨트롤러로 진행
            filterChain.doFilter(request, response);
        } catch (JwtService.InvalidJwtException ex) {
            /**
             * 1) "토큰이 있는데 invalid"면 여기서 응답을 확정하고 끝낸다.
             * SecurityErrorWriter는 ApiError 포맷 + status 설정까지 책임지는 쪽이 좋다.
             * 
             * 2) errorWriter.write(response, ErrorCode.ACCESS_INVALID);
             *    내부에서: write(response, errorCode, errorCode.defaultMessage()); 호출
             */
            SecurityContextHolder.clearContext();
            errorWriter.write(response, ErrorCode.ACCESS_INVALID);
        }
    }

    /**
     * Authorization: Bearer <token> 형태에서 <token>만 추출한다.
     * - 없거나 형식이 다르면 null을 반환한다.
     */
    private String resolveBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank()) return null;
        if (!authHeader.startsWith(BEARER_PREFIX)) return null;

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }

}
