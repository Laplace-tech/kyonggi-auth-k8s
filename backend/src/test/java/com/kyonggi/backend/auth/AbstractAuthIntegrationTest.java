package com.kyonggi.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.kyonggi.backend.auth.domain.User;
import com.kyonggi.backend.auth.repo.EmailOtpRepository;
import com.kyonggi.backend.auth.repo.UserRepository;
import com.kyonggi.backend.auth.support.MailhogSupport;
import com.kyonggi.backend.auth.token.repo.RefreshTokenRepository;
import com.kyonggi.backend.infra.AbstractIntegrationTest;

/**
 * Auth(회원/인증) 통합 테스트들의 공통 베이스 클래스
 * 
 * - Auth 관련 통합테스트들이 매번 '완전히 동일한 초기 상태'에서 시작하도록 만든다.
 * - @BeforeEach: 매 테스트 실행 직전 DB에 남은 데이터, 
 *    이전 테스트에서 발송된 메일을 모두 비우고 기본 유저 1명을 생성해서 저장한다. 
 * 
 * Auth 관련 통합 테스트 클래스들이 extends 해서 그대로 사용한다,
 */
public abstract class AbstractAuthIntegrationTest extends AbstractIntegrationTest {

    protected static final String EMAIL = "add28482848@kyonggi.ac.kr";
    protected static final String PASSWORD = "28482848a!";
    protected static final String NICKNAME = "Anna";

    @Autowired protected UserRepository userRepository;
    @Autowired protected RefreshTokenRepository refreshTokenRepository;
    @Autowired protected EmailOtpRepository emailOtpRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetAuthData() throws Exception {
        // 메일 잔여분 제거 (OTP 테스트 흔들림 방지)
        MailhogSupport.clearAll(); 

        // 테이블 레코드 전체 삭제 (단 FK 걸린 것부터 제거)
        refreshTokenRepository.deleteAll();
        emailOtpRepository.deleteAll();
        userRepository.deleteAll();
    }

    // 유저 생성 유틸
    protected User createUser(String email, String rawPassword, String nickname) {
        return userRepository.save(
                User.create(email, passwordEncoder.encode(rawPassword), nickname)
        );
    }

    // 유저 생성 유틸 (디폴트 값)
    protected User createDefaultUser() {
        return createUser(EMAIL, PASSWORD, NICKNAME);
    }

    protected static String uniqueKyonggiEmail(String prefix) {
        return prefix + "_" + System.nanoTime() + "@kyonggi.ac.kr";
    }

    protected static String uniqueRawKyonggiEmail(String prefix) {
        return "  " + uniqueKyonggiEmail(prefix) + "  ";
    }

}
