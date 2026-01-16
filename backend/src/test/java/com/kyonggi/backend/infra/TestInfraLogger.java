package com.kyonggi.backend.infra;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

@Slf4j
public final class TestInfraLogger {

    private TestInfraLogger() {}

    public static void logContainersStarted(
            MySQLContainer<?> mysql,
            int mysqlContainerPort,       // 3306
            GenericContainer<?> mailhog,
            int mailhogSmtpContainerPort, // 1025
            int mailhogHttpContainerPort  // 8025
    ) {
        log.info("===================================================================");
        log.info("[TEST] Containers started");

        int mysqlHostPort = mysql.getMappedPort(mysqlContainerPort);
        log.info("[TEST] MySQL jdbcUrl={}", mysql.getJdbcUrl());
        log.info("[TEST] MySQL mapping: host {}:{} -> container:{}",
                mysql.getHost(), mysqlHostPort, mysqlContainerPort);

        int smtpHostPort = mailhog.getMappedPort(mailhogSmtpContainerPort);
        int httpHostPort = mailhog.getMappedPort(mailhogHttpContainerPort);

        log.info("[TEST] MailHog SMTP mapping: host {}:{} -> container:{}",
                mailhog.getHost(), smtpHostPort, mailhogSmtpContainerPort);
        log.info("[TEST] MailHog HTTP mapping: host {}:{} -> container:{}",
                mailhog.getHost(), httpHostPort, mailhogHttpContainerPort);

        log.info("===================================================================");
    }

    public static void logOverrideRegistered(MySQLContainer<?> mysql, TestInfraInfo infra) {
        log.info("[TEST] DynamicPropertySource override registered");
        log.info("[TEST] --- Registered override values (dump) ---");

        log.info("[TEST] spring.datasource.url={}", mysql.getJdbcUrl());
        log.info("[TEST] spring.datasource.username={}", mysql.getUsername());
        log.info("[TEST] spring.datasource.password=<hidden>");
        log.info("[TEST] spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver");

        log.info("[TEST] spring.flyway.url={}", mysql.getJdbcUrl());
        log.info("[TEST] spring.flyway.user={}", mysql.getUsername());
        log.info("[TEST] spring.flyway.password=<hidden>");

        log.info("[TEST] spring.datasource.hikari.connection-timeout=30000");
        log.info("[TEST] spring.datasource.hikari.initialization-fail-timeout=-1");

        log.info("[TEST] spring.mail.host={}", infra.mailhogHost());
        log.info("[TEST] spring.mail.port={}", infra.mailhogSmtpHostPort());
        log.info("[TEST] spring.mail.properties.mail.smtp.auth=false");
        log.info("[TEST] spring.mail.properties.mail.smtp.starttls.enable=false");
        log.info("[TEST] spring.mail.properties.mail.smtp.starttls.required=false");

        log.info("[TEST] test.mailhog.base-url={}", infra.mailhogBaseUrl());
        log.info("[TEST] -----------------------------------------");
    }
}
