package com.kyonggi.backend.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.kyonggi.backend.global.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor; 

/**
 * 인증이 필요한 리소스에 "인증 없이" 접근했을 때 호출되는 EntryPoint.
 *
 * - SecurityConfig에서 authenticated()로 보호된 URL인데,
 *    SecurityContext에 Authentication이 비어있을 때 호출된다. (토큰이 아예 없거나 인증이 안 된 상태)
 * 
 * 반대로,
 * - Authorization 헤더는 있는데 JWT가 invalid인 경우 
 *    JwtAuthenticationFilter가 먼저 401을 내려버린다.
 */
@RequiredArgsConstructor
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter errorWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {


        errorWriter.write(response, ErrorCode.AUTH_REQUIRED); // 내부에서: write(response, errorCode, errorCode.defaultMessage()); 호출
    }
}
