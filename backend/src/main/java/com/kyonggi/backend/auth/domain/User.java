package com.kyonggi.backend.auth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * users 테이블 = "회원 저장소"
 * 
 * 가입 흐름: 
 * - OTP 인증 성공 -> SignupService.completeSignup()에서 users에 insert 
 * 
 * 로그인 흐름:
 * - LogonService.login()에서 users를 email로 조회
 * - password_hash 비교
 * - status/role로 인증/인가 정책 적용
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uq_users_nickname", columnNames = "nickname")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // PK. JWT의 sub(subject)로 쓰임(userId)

    @Column(nullable = false, length = 255)
    private String email; // 로그인 ID (Unique)

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash; // 비밀번호를 BCrypt된 해시로 저장 (원문 저장 금지)

    @Column(nullable = false, length = 30) 
    private String nickname; // 커뮤니티 표시명. 유니크

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role; // JWT에 실어서 인가(권한 체크)에 씀

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status; // 사용자 상태 (ACTIVE만 로그인 허용 같은 정책에 씀)

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt; // 운영/보안용(마지막 로그인)

    // 가입 완료 시 생성 (SignupService.completeSignup()에서 호출)
    public static User create(String email, String passwordHash, String nickname) {
        User u = new User();
        u.email = email;
        u.passwordHash = passwordHash;
        u.nickname = nickname;

        // 기본 정책값
        u.role = UserRole.USER;
        u.status = UserStatus.ACTIVE;
        u.lastLoginAt = null;
        return u;
    }

    public void setLastLoginAt(LocalDateTime now) {
        lastLoginAt = now;
    }

    public Long getId() {return id;}
    public UserStatus getStatus() {return status;}
    public UserRole getRole() {return role;}
    public String getPasswordHash() {return passwordHash;}
    public String getEmail() {return email;}
    public String getNickname() {return nickname;}
}
