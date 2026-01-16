package com.kyonggi.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyonggi.backend.global.ApiError;
import com.kyonggi.backend.global.ErrorCode;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Security 레이어(필터/EntryPoint)에서 "일관된 JSON 에러 응답"을 내려주는 유틸
 * 
 * - GlobalExceptionHandler는 @Controller 이후 예외를 처리한다.
 * - 그러나, Security Filter Chain에서 차단되는 요청은 @Controller까지 안 온다.
 *   그래서 Security 영역에서도 ApiError 포맷을 동일하게 맞추기 위해 Writer가 필요하다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter {
    
    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        write(response, errorCode, errorCode.defaultMessage());
    }

    public void write(HttpServletResponse response, ErrorCode errorCode, String messageOverride) throws IOException {
        
        // 이미 다른 필터가 응답을 만들어버린 경우라면 건드리지 않음
        if(response.isCommitted()) 
            return;

        // 인증 실패 응답은 캐시되면 위험/혼란 → 캐시 금지 기본 방어
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");

        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        
        objectMapper.writeValue(
                response.getWriter(),
                ApiError.of(errorCode, messageOverride)
        );
    }

}
