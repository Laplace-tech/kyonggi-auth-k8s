package com.kyonggi.backend.auth.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * @Configuration 
 * - 이 클래스가 "스프링 설정 클래스"임을 의미
 * - @Bean 메서드에서 반환하는 객체들이 스프링 컨테이너(ApplicationContext)에 등록됨
 * 
 * @EnableConfigurationProperties
 *  - @ConfigurationProperties가 붙은 클래스들을 스프링이 자동으로 바인딩 + 검증하도록 활성화
 *  - 여기서는: { OtpProperties, AuthProperties, AppMailProperties }
 */  
@Configuration
@EnableConfigurationProperties({
        OtpProperties.class, 
        AuthProperties.class,
        AppMailProperties.class
})
public class AuthModuleConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * java.time.clock:
     * - 서버 표준 타임존을 KST로 강제한다(프로젝트 정책).
     * - 테스트 환경에서는 TestClockConfig가 별도의 Clock을 제공하기 때문에 
     *    이 @Bean이 안 만들어짐.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class) 
    public Clock clock() {
        return Clock.system(KST);
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
