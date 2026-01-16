package com.kyonggi.backend.infra;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;


import lombok.extern.slf4j.Slf4j;

/**
 * =================================
 *  테스트 실행 커맨드 모음 (옵션 포함)
 * =================================
 * 기본:
 * - graldew test
 * 
 * 깨끗하게 다시 (빌드 산출물까지 리셋):
 * - ./gradlew clean test
 * 
 * 자세한 로그 / 스택트레이스:
 * - ./gradlew test --info
 * - ./gradlew test --stacktrace
 * 
 * 데몬 영향 제거 (환경 일관성):
 * - ./gradlew test --no-daemon
 * 
 * 캐시 때문에 안 바뀌는 것 같은 느낌일 때
 * - ./gradlew test --rerun-tasks
 * 
 * 특정 테스트만 실행 (Gradle --tests 패턴)
 * - Class:    ./gradlew test --tests "com.kyonggi.backend.BackendApplicationTests"
 * - Method:   ./gradlew test --tests "com.kyonggi.backend.ActuatorHealthIT.health_up"
 * - Asterisk: ./gradlew test --tests "*Auth*"
 *             ./gradlew test --tests "com.kyonggi.backend.auth.*"
 * 
 * 테스트 리포트 열기
 * - wslview build/reports/tests/test/index.html
 * 
 * =============================
 *  코드 검색 (설정/상수/정책 찾기)
 * =============================
 * 1) grep 기본형:
 * - grep -Rni --include="*.java" "AuthProperties" .
 * - grep -Rnw --include="*.java" "EMAIL" .
 * 
 * -R: 재귀 탐색
 * -n: 라인 번호
 * -i: 대소문자 무시(없으면 구분)
 * -w: "단어 단위" 매칭(경계 기준)
 * --include="*.java": 자바 파일만
 * 
 * 2) ripgrep(rg):
 * - rg - n "AuthProperties" .
 * - rg -n --word-regexp "EMAIL" .
 * - rg -n -i "email" .
 * 
 * ============================
 *   테스트 실행 흐름(호출 순서)
 * ============================
 * 1) [JVM - 클래스 로드 단계]
 *    - JUnit이 테스트 클래스를 로드하는 순간 static 초기화 블록이 즉시 실행된다.
 *    - 그래서 static { startContainersOnce(); } 에서 찍는 컨테이너/포트매핑 로그가 제일 먼저 나온다.
 *
 *      클래스 로드
 *        → static { startContainersOnce(); }
 *        → MYSQL.start(), MAILHOG.start()
 *        → 컨테이너 매핑 로그 출력 (hostPort <-> containerPort)
 *
 * 2) [Spring - ApplicationContext 생성 단계]
 *    - 그 다음에야 @SpringBootTest가 동작해서 Spring이 컨텍스트를 만들기 시작한다.
 *    - 이 과정에서 Spring Test가 @DynamicPropertySource 메서드를 찾아 호출한다.
 *    - 여기서 하는 일은 "적용"이 아니라 "등록(registry에 Supplier 걸기)"이다.
 *
 *      Spring이 컨텍스트 준비 시작
 *        → @DynamicPropertySource overrideProps(registry) 호출
 *        → registry.add(...) 로 값 등록(예약)
 *        → (여기서 찍는 덤프 로그는 '우리가 등록한 값' 확인용)
 *
 *    ✅ 그래서 "설정값 오버라이드 완료" 로그가 컨테이너 로그보다 늦게 나오는 게 정상이다.
 *       (static은 JVM, DynamicPropertySource는 Spring 컨텍스트 단계)
 *
 * 3) [Spring Boot 초기화 단계]
 *    - Spring은 등록된 값(override된 값)을 이용해 DataSource/Flyway/MailSender 등을 초기화한다.
 *
 *      Environment 구성 → DataSource/Flyway/MailSender init
 *        → DB 연결 / Flyway migration / MailSender 준비
 *
 * 4) [테스트 메서드 실행 직전 단계]
 *    - 각 테스트 메서드 직전에 @BeforeEach가 실행된다.
 *    - 즉 resetTestClock()은 "컨텍스트 생성 이후" + "매 테스트 직전"에 실행되는 것이다.
 *
 *      각 @Test 실행 직전
 *        → @BeforeEach resetTestClock()
 */

@Slf4j
@SpringBootTest                 // 실제 스프링 애플리케이션을 통째로 띄움
@AutoConfigureMockMvc           // 실제 톰캣을 띄우지 않고도 HTTP 요청/응답을 흉내내는 MockMvc를 주입
@ActiveProfiles("test")         // application.yml + application-test.yml 조합을 활성화: "test 프로필"이 켜짐
@Import(TestClockConfig.class)  // 테스트에서만 쓰는 Clock Bean을 주입한다.
public abstract class AbstractIntegrationTest {

    private static final String MYSQL_IMAGE = "mysql:8.0.36";
    private static final String MYSQL_DB = "kyonggi_board_test";
    private static final String MYSQL_USER = "kyonggi";
    private static final String MYSQL_PASSWORD = "kyonggi";
    private static final int MYSQL_CONTAINER_PORT = 3306; // MySQL 컨테이너 내부 포트

    private static final String MAILHOG_IMAGE = "mailhog/mailhog:v1.0.1";
    private static final int MAILHOG_SMTP_CONTAINER_PORT = 1025; // MailHog SMTP가 리슨하는 포트
    private static final int MAILHOG_HTTP_CONTAINER_PORT = 8025; // MailHog UI/API가 리슨하는 포트

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName(MYSQL_DB)
            .withUsername(MYSQL_USER)
            .withPassword(MYSQL_PASSWORD)
            .withStartupAttempts(3)
            .withStartupTimeout(Duration.ofMinutes(2));

    static final GenericContainer<?> MAILHOG = new GenericContainer<>(MAILHOG_IMAGE)
            .withExposedPorts(
                    MAILHOG_SMTP_CONTAINER_PORT, // host:7878 -> container:1025
                    MAILHOG_HTTP_CONTAINER_PORT  // host:7877 -> container:8025
            )
            .waitingFor(
                    // MailHog HTTP API가 실제로 200을 줄 때까지 대기
                    Wait.forHttp("/api/v2/messages")
                            .forPort(MAILHOG_HTTP_CONTAINER_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(30)));

    static {
        startContainersOnce();
    }

    @BeforeEach
    void resetTestClock() {
        TestClockConfig.reset(); // 매 테스트 메서드마다 Clock을 초기화
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {

        startContainersOnce(); // 안전하게 (멱등 보장)

        TestInfraInfo infra = TestInfraInfo.fromMailhog(
                MAILHOG,
                MAILHOG_SMTP_CONTAINER_PORT,
                MAILHOG_HTTP_CONTAINER_PORT);

        TestDynamicProperties.overrideProps(r, MYSQL, infra);
    }

    private static void startContainersOnce() {
        if (!STARTED.compareAndSet(false, true)) {
            return; // 이미 시작됨
        }

        try {
            MYSQL.start();
            MAILHOG.start();

            TestInfraLogger.logContainersStarted(
                    MYSQL,
                    MYSQL_CONTAINER_PORT, // 3306
                    MAILHOG,
                    MAILHOG_SMTP_CONTAINER_PORT, // 1025
                    MAILHOG_HTTP_CONTAINER_PORT  // 8025
            );

        } catch (Exception e) {
            log.error("❌ Testcontainer init failed", e);
            throw new IllegalStateException("❌ Testcontainer init failed", e);
        }
    }

    // ---- helper method ----
    public static String getMailhogHost() {
        return MAILHOG.getHost();
    }

    public static int getMappedMailhogSmtpPort() {
        return MAILHOG.getMappedPort(MAILHOG_SMTP_CONTAINER_PORT);
    }

    public static int getMappedMailhogHttpPort() {
        return MAILHOG.getMappedPort(MAILHOG_HTTP_CONTAINER_PORT);
    }

    public static String getMailhogBaseUrl() {
        return "http://" + getMailhogHost() + ":" + getMappedMailhogHttpPort();
    }
}