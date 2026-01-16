package com.kyonggi.backend.global;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * 비즈니스 로직에서 사용하는 실무형 커스텀 예외 (중앙화된 ErrorCode 기반)
 * 
 * - 서비스/도메인 정책 위반을 ErrorCode로 표현한다.
 * - 전역 핸들러(GlobalExceptionHandler) / 보안 레이어(SecurityErrorWriter)가
 *   이 예외를 ApiError로 직렬화해 응답 포맷을 고정한다.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status; // ex: HttpStatus.UNAUTHORIZED
    private final String code;       // ex: "REFRESH_EXPIRED"
    private final Integer retryAfterSeconds;
    private final Object details;

    /**
     * 실무형(중앙화된 ErrorCode 기반)
     * - 서비스 계층에서 예외 발생 시, 아래와 같은 방식으로 예외를 생성해 던짐.
     * => throw new ApiException(ErrorCode.REFRESH_EXPIRED);
     */
    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public ApiException(ErrorCode errorCode, String messageOverride) {
        this(errorCode, messageOverride, null, null);
    }

    public ApiException(ErrorCode errorCode, Integer retryAfterSeconds) {
        this(errorCode, errorCode.defaultMessage(), retryAfterSeconds, null);
    }

    public ApiException(ErrorCode errorCode, Integer retryAfterSeconds, Object details) {
        this(errorCode, errorCode.defaultMessage(), retryAfterSeconds, details);
    }

    public ApiException(ErrorCode errorCode, String messageOverride, Integer retryAfterSeconds, Object details) {
        // super(...)는 첫 줄이어야 해서 errorCode null 검사보다 앞에 둘 수밖에 없음.
        super(resolveMessage(errorCode, messageOverride));

        if (errorCode == null) 
            throw new IllegalArgumentException("ErrorCode must not be null");

        this.status = errorCode.status();
        this.code = errorCode.name();
        this.retryAfterSeconds = retryAfterSeconds;
        this.details = details;
    }

    private static String resolveMessage(ErrorCode errorCode, String messageOverride) {
        if (messageOverride != null && !messageOverride.isBlank()) {
            return messageOverride;
        }
        return (errorCode == null) ? null : errorCode.defaultMessage();
    }
}


/**
 * 비즈니스 로직에서 사용하는 실무형 커스텀 예외 (중앙화된 ErrorCode 기반)
 * 
 * - 서비스 계층에서 정책 위반을 발견하면, 아래와 같은 방식으로 예외를 생성해 던짐.
 *  => throw new ApiException(ErrorCode.REFRESH_EXPIRED);
 * 
 *  이때, ErrorCode는 (HttpStatus status, String defaultMessage) 필드로 구성되어 있으므로
 *   ApiException 예외 클래스에 다음과 같이 값을 채워넣음
 * 
 *      class ApiException {
 *          super(message) : message = errorCode.defaultMessage() // "리프레쉬 토큰이 만료되었습니다."
 *          this.status = errorCode.status()           // HttpStatus.UNAUTHORIZED
 *          this.code = errorCode.name()               // REFRESH_EXPIRED
 *          this.retryAfterSeconds = <option1>
 *          this.details = <option2>
 *      }
 * 
 *   위와 같이 필드에 값을 채우고 예외를 throw로 날리면 GlobalExceptionHandler가 해당 예외를 잡는다.
 *    전역 예외 처리기(GlobalExceptionHandler)는 클라이언트에게 JSON으로 전송할 ApiError 객체를 만들어
 *    ResponseEntity<ApiError>에 담아 반환한다.
 *  
 *      public ResponseEntity<ApiError> handleApiException(ApiException e) {
 *          ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus()); // HttpStatus.UNAUTHORIZED
 *          return builder.body(new ApiError(e.getCode(),    // REFRESH_EXPIRED
 *                                           e.getMessage(), // "리프레쉬 토큰이 만료되었습니다."
 *                                           e.getRetryAfterSeconds(), 
 *                                           e.getDetails()));
 *      }
 */