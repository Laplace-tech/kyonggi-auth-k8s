package com.kyonggi.backend.global;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드(클라이언트 분기용) + HTTP 상태 + 기본 메시지의 단일 소스.
 *
 * 원칙:
 * - code = enum name() (변경 시 API 계약 깨짐)
 * - status/message는 정책에 따라 바뀔 수 있지만, code는 최대한 고정한다.
 */
public enum ErrorCode {

    // Signup / Email policy
    EMAIL_DOMAIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST,
            "@kyonggi.ac.kr 이메일만 가입할 수 있습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT,
            "이미 가입된 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT,
            "이미 사용 중인 닉네임입니다."),

    // OTP
    OTP_ALREADY_VERIFIED(HttpStatus.CONFLICT,
            "이미 인증이 완료되었습니다. 회원가입을 완료해주세요."),
    OTP_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS,
            "잠시 후 다시 시도해주세요."),
    OTP_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS,
            "일일 OTP 발송 한도를 초과했습니다. 내일 다시 시도해주세요."),
    OTP_NOT_FOUND(HttpStatus.BAD_REQUEST,
            "OTP 요청 이력이 없습니다. 먼저 인증번호를 요청해주세요."),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST,
            "인증번호가 만료되었습니다. 다시 요청해주세요."),
    OTP_TOO_MANY_FAILURES(HttpStatus.TOO_MANY_REQUESTS,
            "인증 시도 횟수를 초과했습니다. 인증번호를 다시 요청해주세요."),
    OTP_INVALID(HttpStatus.BAD_REQUEST,
            "인증번호가 올바르지 않습니다."),
    OTP_NOT_VERIFIED(HttpStatus.BAD_REQUEST,
            "OTP 인증을 먼저 완료해주세요."),

    // Signup input policy
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST,
            "비밀번호가 일치하지 않습니다."),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST,
            "비밀번호는 9~15자, 영문+숫자+특수문자를 포함하고 공백이 없어야 합니다."),
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST,
            "닉네임은 2~20자, 한글/영문/숫자/_(언더스코어)만 허용하며 공백은 불가합니다."),

    // Login
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN,
            "사용할 수 없는 계정 상태입니다."),

    // Auth / Security
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED,
            "인증이 필요합니다."),
    ACCESS_INVALID(HttpStatus.UNAUTHORIZED,
            "엑세스 토큰이 유효하지 않습니다."),

    // Refresh token
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED,
            "리프레시 토큰이 유효하지 않습니다."),
    REFRESH_EXPIRED(HttpStatus.UNAUTHORIZED,
            "리프레시 토큰이 만료되었습니다."),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED,
            "리프레시 토큰이 유효하지 않습니다."), // 보안상 메시지 뭉개기
    REFRESH_REVOKED(HttpStatus.UNAUTHORIZED,
            "리프레시 토큰이 유효하지 않습니다."), // 보안상 메시지 뭉개기

    // User / Data consistency
    USER_NOT_FOUND(HttpStatus.UNAUTHORIZED,
            "사용자를 찾을 수 없습니다."),

    // Validation / Common
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST,
            "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
