package com.kyonggi.backend.auth.token.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * refresh_tokens 테이블 매핑 엔티티 (서버가 관리하는 로그인 세션)
 * 
 * 이 엔티티는 "로그인 세션"에 해당한다.
 * - Access Token(JWT)은 서버에 저장하지 않음(Stateless)
 * - Refresh Token은 서버가 DB로 상태 관리
 * 
 * 핵심 보안 불변 조건(invariants):
 * 
 * 1) refresh raw(원문)은 DB에 절대 저장하지 않는다. (token_hash만 저장)
 * 2) rotated 시 기존에 발행된 토큰은 ROTATED로 revoke된다.
 * 3) ROTATED 된 토큰이 다시 제출되면 "재사용 공격"으로 보고 차단한다 (REFRESH_REUSED)
 * 
 * 인덱스:
 * @Index: idx_refresh_token_hash 
 *  - token_hash: 쿠키에서 refresh 토큰 원문을 추출한 뒤 해싱한 값
 *  - 해싱된 문자열이 곧 DB에서 쓸 조회 키이므로 유니크 인덱스 필수 
 * @Index: idx_refresh_user_id 
 *  - user_id: 유저 단위 세션 관리/정리용 인덱스 권장
 */
@Getter
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_refresh_user_id", columnList = "user_id")
    }
)
@NoArgsConstructor(access=AccessLevel.PROTECTED) // JPA가 리플렉션으로 객체 생성
public class RefreshToken {

    // ---- constants (DB 제약과 반드시 맞춰야 함) ----
    public static final int TOKEN_HASH_LEN = 64;      // sha256 hex
    public static final int USER_AGENT_MAX = 255;
    public static final int IP_ADDRESS_MAX = 45;

    private static final String HEX64_REGEX = "^[0-9a-f]{64}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId; 

    /**
     * - sha256 hex(64). raw 저장 금지.
     * - MySQL 기준 char(64)로 고정(정렬/공간 예측 가능).
     * - unique는 @Index(unique=true)로 보장한다고 가정하고 컬럼 unique는 중복이라 제거.
     */
    @Column(name = "token_hash", nullable = false, length = TOKEN_HASH_LEN, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "remember_me", nullable = false)
    private boolean rememberMe;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ---- ops / security telemetry ----
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    // EnumType.STRING: 컬럼은 문자열이지만 자바 쪽은 enum으로 강제
    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 50)
    private RefreshRevokeReason revokeReason; // (기존 DB 값이 "ROTATED", "LOGOUT"이면 그대로 매핑)

    @Column(name = "user_agent", length = USER_AGENT_MAX)
    private String userAgent;

    @Column(name = "ip_address", length = IP_ADDRESS_MAX)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;


    
    // ========= factory =========

    // 발급 팩토리 메서드 (referenced by RefreshTokenService.class)
    public static RefreshToken issue(
            Long userId,
            String tokenHash,
            boolean rememberMe,
            LocalDateTime now,
            LocalDateTime expiresAt
    ) {
        require(userId != null, "userId must not be null");
        require(now != null, "now must not be null");
        require(expiresAt != null, "expiresAt must not be null");
        require(expiresAt.isAfter(now), "expiresAt must be after now");

        String h = requireTokenHash(tokenHash);

        RefreshToken rt = new RefreshToken();
        rt.userId = userId;
        rt.tokenHash = h;
        rt.rememberMe = rememberMe;
        rt.createdAt = now;
        rt.expiresAt = expiresAt;
        return rt;
    }

    /**
     * revoke: 멱등. 이미 revoked면 변경하지 않는다.
     * (rotate/revoke 동시성은 repository의 SELECT ... FOR UPDATE로 직렬화한다.)
     */
    public void revoke(LocalDateTime now, RefreshRevokeReason reason) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (this.revokedAt != null) return;
        this.revokedAt = now;
        this.revokeReason = reason;
    }

    public void touch(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        this.lastUsedAt = now;
    }


    
    // ========= domain =========
    public boolean isExpired(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isRotated() {
        return revokeReason == RefreshRevokeReason.ROTATED;
    }



    // ========= helpers =========
    private static String requireTokenHash(String tokenHash) {
        require(tokenHash != null, "tokenHash must not be null");
        String h = tokenHash.trim();
        require(h.length() == TOKEN_HASH_LEN, "tokenHash must be 64 chars");
        require(h.matches(HEX64_REGEX), "tokenHash must be lowercase hex(64)");
        return h;
    }

    private static String trimToNullAndMax(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }
}
