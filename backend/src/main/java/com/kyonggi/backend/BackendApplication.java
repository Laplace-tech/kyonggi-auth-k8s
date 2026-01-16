package com.kyonggi.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.kyonggi.backend.auth.config.AuthModuleConfig;

/*
MailHog: http://localhost:8025/
- 여기서 OTP 메일이 실제로 발송된 것처럼 쌓인다.
- 스프링은 SMTP로 mailhog(1025)로 보내고, mailhog가 UI(8025)로 보여준다.

================================================================================
[도커 리셋/기동] (로컬 개발 컨테이너 환경 초기화 + 재빌드)
================================================================================
cd ~/kyonggi-board/infra
sudo docker compose down -v
- down: 컨테이너/네트워크 종료 및 제거
- v: 볼륨(mysql_data)까지 날림 -> DB 데이터까지 초기화(가입한 유저/토큰/otp 테이블 다 사라짐)

sudo docker compose up -d --build
- -d: 백그라운드 실행
- --build: backend 이미지 재빌드 (코드 바뀐 경우 반영)

sudo docker compose up -d --build backend
- mysql/mailhog는 그대로 두고 backend만 다시 빌드해서 반영

sudo docker compose ps
- 서비스 상태 확인 (mysql healthcheck 통과 여부 포함)

sudo docker compose logs -f backend
- backend 컨테이너 로그 실시간 추적

================================================================================
[포트 및 프로세스 확인] (충돌/점유/바인딩 문제 체크)
================================================================================
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
- 컨테이너 포트 매핑 확인

sudo lsof -i :8080
- 호스트에서 8080을 누가 점유 중인지 확인
- “이미 다른 프로세스가 8080을 잡고 있어서 compose backend가 못 뜨는” 경우 잡아냄

sudo lsof -i
- 전체 리슨 포트/연결 확인

================================================================================
[curl 시나리오 테스트]  (회원가입/로그인/refresh/me/logout 흐름 검증)
================================================================================
# OTP 요청 204
curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr"}'
- -i: 응답 헤더까지 출력(상태코드 확인)
- 204: No Content (바디 없이 성공)

# OTP 검증 204
curl -i -X POST "http://localhost:8080/auth/signup/otp/verify" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr","code":"921071"}'
- MailHog에서 본 OTP 코드로 verify
- 실패하면 보통: 만료/횟수초과/코드불일치/쿨다운 위반 등의 에러로 떨어짐

# 가입 완료 201
curl -i -X POST "http://localhost:8080/auth/signup/complete" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr","password":"28482848a!","passwordConfirm":"28482848a!","nickname":"Anna"}'
- 201: Created (리소스 생성)
- 여기서 “OTP verified 상태가 아니면” 가입이 막히게 구현돼 있어야 정상

# 로그인 (쿠키 파일에 저장) 200
curl -i -X POST "http://localhost:8080/auth/login" \
  -H "Content-Type: application/json" \
  -c /tmp/kyonggi_cookie.txt \
  -d '{"email":"add28482848@kyonggi.ac.kr","password":"28482848a!","rememberMe":false}'
- -c: 서버가 내려준 Set-Cookie(리프레시 토큰 쿠키)를 파일에 저장
- 바디에는 보통 accessToken(JSON) / 헤더 Set-Cookie에는 refresh가 내려옴(프로젝트 설계 기준)

# 쿠키 파일 내용 확인(저장된 refresh 확인)
cat /tmp/kyonggi_cookie.txt
- Netscape cookie jar 포맷이라 한 줄이 길어도 정상
- 여기서 KG_REFRESH 값이 있는지 확인

# /auth/me (accessToken 넣어서)
curl -i -X GET "http://localhost:8080/auth/me" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJreW9uZ2dpLWJvYXJkIiwic3ViIjoiMiIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzY4MjI4NTA5LCJleHAiOjE3NjgyMjk0MDl9.cUM1AnqUa-UKUMxN9mhady6mHciS5rHDs22sIaqoZMM"
- accessToken(Bearer) 검증 성공하면 내 정보 반환
- 만료/서명불일치면 401
- 여기서 Security/JWT 필터가 실제로 동작하는지 확인하는 핵심 엔드포인트

# refresh (기존 쿠키 보내고, 새 쿠키로 덮어쓰기)
curl -i -X POST "http://localhost:8080/auth/refresh" \
  -b /tmp/kyonggi_cookie.txt \
  -c /tmp/kyonggi_cookie_new.txt
- -b: “저장된 쿠키를 요청에 실어보냄”
- -c: 서버가 새 Set-Cookie를 내려주면 “새 파일에 저장”
- 즉 refresh 로테이션이면:
  - 요청: old refresh 쿠키
  - 응답: new refresh 쿠키(새로 발급) + access 재발급(설계에 따라 body/헤더)

# 새 쿠키 확인
cat /tmp/kyonggi_cookie_new.txt

# 로그아웃 (쿠키 보내고, 서버가 만료 Set-Cookie 내려줌 + 쿠키파일 갱신)
curl -i -X POST "http://localhost:8080/auth/logout" \
  -b /tmp/kyonggi_cookie_new.txt \
  -c /tmp/kyonggi_cookie_after_logout.txt
- 서버는 보통 “쿠키 만료(Set-Cookie: Max-Age=0 등)”를 내려줌
- 그래서 파일을 다시 저장하면 값이 비거나 만료로 바뀌는 게 정상

# 로그아웃 후 쿠키 파일 확인(대부분 만료/빈값으로 바뀜)
cat /tmp/kyonggi_cookie_after_logout.txt

================================================================================
[DB 확인]
================================================================================
dmysql -e "select * from users;"
dmysql -e "select * from email_otp;"
dmysql -e "select * from refresh_tokens;"
- 만약 dmysql이 없다면 보통 이렇게 대체 가능:
  docker exec -i kyonggi-mysql mysql -ukyonggi -pkyonggi kyonggi_board -e "select * from users;"

================================================================================
기타 유틸
================================================================================
./gradlew clean test --stacktrace
- 테스트 전체 실행 + 실패 원인 상세 스택 출력

grep -Rni --include="*.java" "AuthProperties" .
- 설정 클래스 참조 위치를 빠르게 찾기

grep -Rnw --include="*.java" "EMAIL" .
- 대소문자 구분

rm /tmp/*.txt
- curl 쿠키 파일 정리

*/


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
 *    infra/.env -> docker-compose.yml -> (컨테이너 OS 환경변수) -> application.yml -> @ConfigurationProperties
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
