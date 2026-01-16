package com.kyonggi.backend.global;

import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * 공통 API 에러 응답 DTO
 * 
 * 원칙:
 * - 모든 레이어(ControllerAdvice / EntryPoint / Filter)에서  에러 응답엗 대하여
 *   항상 동일한 JSON 스키마 포맷를 유지한다.
 *
 * 필드:
 * - code: 클라이언트 분기용 안정 식별자 (ErrorCode.name())
 * - message: 사용자 메시지(정책/로케일에 따라 바뀔 수 있음)
 * - retryAfterSeconds: 재시도 가능 시간(주로 429)
 * - details: 디버깅/추가 정보(필요 시만)
 * 
 * 1) record <className>(...)
 *  - 불변 데이터 묶음, 단순 데이터 전달용 DTO
 * 
 * 2) @JsonInclude(NON_NULL)
 *  - JSON으로 내려줄 때 null인 필드는 전부 제외시키기
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,    // ex: "INVALID_CREDENTIALS"
        String message, // ex: "이메일 또는 비밀번호가 올바르지 않습니다."
        Integer retryAfterSeconds, 
        Object details
) {
    public static ApiError of(ErrorCode errorCode) {
        return new ApiError(errorCode.name(), errorCode.defaultMessage(), null, null);
    }

    public static ApiError of(ErrorCode errorCode, String messageOverride) {
        return new ApiError(errorCode.name(), messageOverride, null, null);
    }

    public static ApiError of(ErrorCode errorCode, Integer retryAfterSeconds) {
        return new ApiError(errorCode.name(), errorCode.defaultMessage(), retryAfterSeconds, null);
    }

    public static ApiError of(ErrorCode errorCode, Integer retryAfterSeconds, Object details) {
        return new ApiError(errorCode.name(), errorCode.defaultMessage(), retryAfterSeconds, details);
    }

    public static ApiError from(ApiException e) {
        return new ApiError(e.getCode(), e.getMessage(), e.getRetryAfterSeconds(), e.getDetails());
    }

}
