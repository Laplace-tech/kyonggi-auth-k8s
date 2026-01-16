package com.kyonggi.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * # [Application Domain Config]
 * 
 * app:
 *   mail:
 *     from: ${APP_MAIL_FROM:? set APP_MAIL_FROM (local)}
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record AppMailProperties(
                @NotBlank @Email String from) {
}