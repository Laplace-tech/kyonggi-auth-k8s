package com.kyonggi.backend.auth.identity.signup.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.kyonggi.backend.auth.config.AppMailProperties;
import com.kyonggi.backend.auth.config.OtpProperties;

import lombok.RequiredArgsConstructor;

/**
 * 회원 가입 OTP 메일 발송 어댑터
 * 
 * @Component
 * - 비즈니스 서비스가 아닌 "외부 I/O 어댑터"
 * - 서비스(정책)와 메일 발송이라 기술적 관심사를 분리한다.
 * 
 * @Service가 직접 JavaMailSender를 쓰지 않고 
 *  이 클래스를 거친다 -> 관심사 분리 (SRP) 
 */
@Component
@RequiredArgsConstructor
public class SignupMailSender {

    private static final String SUBJECT = "[경기대 커뮤니티] 회원가입 인증번호";

    private final JavaMailSender mailSender;
    private final OtpProperties props;
    private final AppMailProperties mailProps;

    public void sendOtp(String toEmail, String code) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(toEmail);
        msg.setFrom(mailProps.from());  // ✅ 핵심
        msg.setSubject(SUBJECT);
        msg.setText(buildBody(code));
        mailSender.send(msg);
    }

    private String buildBody(String code) {
        return "인증번호: " + code + "\n\n" + props.ttlMinutes() + "분 이내에 입력해주세요.";
    }
}