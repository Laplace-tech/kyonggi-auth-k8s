package com.kyonggi.backend.infra;

import org.testcontainers.containers.GenericContainer;

/**
 * 테스트 인프라(메일호그) 접근 정보 스냅샷.
 * - host/port/baseUrl 계산 책임을 여기로 몰아넣는다.
 */
public record TestInfraInfo(
        String mailhogHost,      // localhost
        int mailhogSmtpHostPort, // ????? :: 1025
        int mailhogHttpHostPort  // ????? :: 8025 
) {
    public String mailhogBaseUrl() {
        return "http://" + mailhogHost + ":" + mailhogHttpHostPort;
    }

    public static TestInfraInfo fromMailhog(
            GenericContainer<?> mailhog,
            int mailhogSmtpContainerPort,  // 컨테이너 내부 SMTP 포트: 1025
            int mailhogHttpContainerPort   // 컨테이너 내부 HTTP 포트: 8025
    ) {
        String host = mailhog.getHost();
        int smtpHostPort = mailhog.getMappedPort(mailhogSmtpContainerPort);
        int httpHostPort = mailhog.getMappedPort(mailhogHttpContainerPort);
        return new TestInfraInfo(host, smtpHostPort, httpHostPort);
    }
}
