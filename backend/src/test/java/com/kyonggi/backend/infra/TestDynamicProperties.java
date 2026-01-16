package com.kyonggi.backend.infra;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;

public final class TestDynamicProperties {

    private TestDynamicProperties() {}

    public static void overrideProps(DynamicPropertyRegistry r, MySQLContainer<?> mysql, TestInfraInfo infra) {

        // --- DB ---
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // --- Flyway ---
        r.add("spring.flyway.url", mysql::getJdbcUrl);
        r.add("spring.flyway.user", mysql::getUsername);
        r.add("spring.flyway.password", mysql::getPassword);

        // --- Hikari ---
        r.add("spring.datasource.hikari.connection-timeout", () -> "30000");
        r.add("spring.datasource.hikari.initialization-fail-timeout", () -> "-1");

        // --- Mailhog ---
        r.add("spring.mail.host", infra::mailhogHost);         // localhost
        r.add("spring.mail.port", infra::mailhogSmtpHostPort); // ????? :: 1025

        r.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");

        // --- test helper ---
        r.add("test.mailhog.base-url", infra::mailhogBaseUrl); // http://localhost:?????

        // ✅ “등록”은 여기서 끝. (이 타이밍이 맞음)
        TestInfraLogger.logOverrideRegistered(mysql, infra);
    }
}
