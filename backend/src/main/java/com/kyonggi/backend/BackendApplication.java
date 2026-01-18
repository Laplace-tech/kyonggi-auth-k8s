package com.kyonggi.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.kyonggi.backend.auth.config.AuthModuleConfig;

/**
 * 이 애플리케이션 클래스(엔트리포인트)의 역할
 * - Spring Boot "부팅 시작점", main()에서 SpringApplication.run
 * - @SpringBootApplication이 붙은 패키지(com.kyonggi.backend) 기준으로
 *   하위 패키지(com.kyonggi.backend.*)를 컴포넌트 스캔한다.
 * - 즉 auth, security, global 등 전부 com.kyonggi.backend 아래에 있으면 자동으로 스캔 대상.
 * 
 * ------------------------------------------------------------------------------------
 * 설정 값 주입 흐름 (도커/로컬):
 * ------------------------------------------------------------------------------------
 *    ops/compose/secrets/.env -> docker-compose.yml -> (컨테이너 OS 환경변수) -> application.yml -> @ConfigurationProperties
 * 
  * 1) infra/.env
 * - “도커 컴포즈가 변수 치환할 때” 쓰는 파일
 * - .env에 적었다고 컨테이너 환경변수로 자동 주입되는 게 아니다.
 *    docker-compose.yml의 environment: 에서 ${VAR} 형태로 넘겨줘야 컨테이너가 가진다.
 *
 * 2) infra/docker-compose.yml
 * - backend 컨테이너에 환경변수를 주입한다.
 *   SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/...
 *   APP_AUTH_JWT_SECRET=${APP_AUTH_JWT_SECRET:?required}
 * - 여기서 :?required 는 “컴포즈 실행 단계에서” 누락 시 즉시 실패.
 *
 * 3) backend application.yml
 * - ${ENV:default} 문법은 “스프링이 환경변수부터 찾고 없으면 default 사용”
 * - 즉 compose에서 환경변수를 넣어주면, application.yml의 기본값은 사실상 fallback 용도.
 *
 * 4) @ConfigurationProperties 바인딩
 * - 최종적으로 app.auth.*, app.otp.* 같은 설정이 AuthProperties/OtpProperties에 타입 안전하게 들어간다.
 * - @Validated + Bean Validation이 걸려있으면 규칙 위반 시 “부팅 실패(Fail-fast)”가 나야 정상.
 *
 * ------------------------------------------------------------------------------------
 * 운영/디버깅 체크 포인트 (로그로 바로 확인)
 * ------------------------------------------------------------------------------------
 * - 프로필:
 *     "The following 1 profile is active: \"local\""
 * - DB 연결 여부:
 *     HikariPool started / Added connection
 * - Flyway 적용 여부:
 *     Successfully applied ... migration
 * 
 * - (주의) "Using generated security password" 경고: 기본 유저 자동생성
 *     UserDetailsService 자동설정이 살아있다는 뜻(=기본 인메모리 유저 생성).
 *     JWT 방식이면 기능적으로 치명적이진 않지만, 의도치 않은 보안 자동설정 신호라 정리 대상.
 *      => @SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})로 AutoConfig 끄기  
 */

@Import(AuthModuleConfig.class)
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
