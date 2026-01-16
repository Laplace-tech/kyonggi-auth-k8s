package com.kyonggi.backend.auth.token.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kyonggi.backend.auth.config.AuthProperties;
import com.kyonggi.backend.auth.domain.User;
import com.kyonggi.backend.auth.repo.UserRepository;
import com.kyonggi.backend.auth.token.domain.RefreshRevokeReason;
import com.kyonggi.backend.auth.token.domain.RefreshToken;
import com.kyonggi.backend.auth.token.repo.RefreshTokenRepository;
import com.kyonggi.backend.auth.token.support.TokenGenerator;
import com.kyonggi.backend.auth.token.support.TokenHashUtils;
import com.kyonggi.backend.global.ApiException;
import com.kyonggi.backend.global.ErrorCode;
import com.kyonggi.backend.security.JwtService;

import lombok.RequiredArgsConstructor;

/**
 * Refresh Token 발급/로테이션 서비스
 * 
 * - DB에는 refresh raw를 저장하지 않고 sha256(token_hash)만 저장한다.
 * - rotate 시 old는 ROTATED로 폐기하고, 새 refresh를 발급한다.
 * - ROTATED 토큰이 다시 제출되면 재사용 공격/중복제출로 보고 REFRESH_REUSED로 차단한다.
 * 
 * 동시성:
 * - old row를 SELECT ... FOR UPDATE(PESSIMISTIC_WRITE)로 잠가
 *    같은 Row에 동시 여러 트랜잭션의 접근을 막는다
 *
 * rememberMe 정책
 * - rememberMe=true → rememberMeSeconds
 * - rememberMe=false → sessionTtlSeconds
 */ 
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    private final JwtService jwtService;

    private final TokenGenerator tokenGenerator;

    private final AuthProperties props;       
    private final Clock clock;                 

    // 리프레쉬 토큰 발급
    @Transactional
    public Issued issue(Long userId, boolean rememberMe) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");

        LocalDateTime now = LocalDateTime.now(clock);
        long ttlSeconds = resolveTtlSeconds(rememberMe);
        LocalDateTime expiresAt = now.plusSeconds(ttlSeconds);

        String raw = tokenGenerator.generateRefreshToken();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("generated refresh token is blank");
        }

        String hash = TokenHashUtils.sha256Hex(raw);

        RefreshToken newRefreshToken = RefreshToken.issue(userId, hash, rememberMe, now, expiresAt);
        refreshTokenRepository.save(newRefreshToken);

        return new Issued(raw, expiresAt, rememberMe); // 토큰의 원문을 쿠키로 내려줘야 하므로 raw를 반환한다.
    }

    // 리프레쉬 토큰 재발급
    @Transactional
    public RotateResult rotate(String oldRefreshRaw) {
        if (oldRefreshRaw == null || oldRefreshRaw.isBlank()) {
            throw new ApiException(ErrorCode.REFRESH_INVALID); // @DisplayName("리프레시: 쿠키 없음 → 401 REFRESH_INVALID")
        }
        
        LocalDateTime now = LocalDateTime.now(clock);

        /**
         * oldRefreshRaw를 해싱하여 DB로 (Unique)조회 
         * - LockModeType.PESSIMISTIC_WRITE: 
         *    old refresh row를 PESSIMISTIC_WRITE로 잠가서 같은 토큰을 두 번 성공하는 것을 구조적으로 차단한다.
         */
        String hash = TokenHashUtils.sha256Hex(oldRefreshRaw);
        RefreshToken oldRefreshToken = refreshTokenRepository.findByTokenHash(hash)
                                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID)); // @DisplayName("리프레시: 미발급 refresh 토큰 → 401 REFRESH_INVALID")


        // 1) verifying: Rotated(폐기), Revoked(로그아웃 등), Expiry
        /**
         * verifying:
         * - isRotated: 이미 폐기된 리프레쉬 토큰인지 검증 재사용 (토큰 재사용으로 인한 공격행위로 판단)
         * - isRevoked: 로그아웃 등으로 폐기된 리프레쉬 토큰인지 검증
         * - isExpired: 만료 날짜가 지난 토큰인지 검증
         */
        if (oldRefreshToken.isRotated()) throw new ApiException(ErrorCode.REFRESH_REUSED); // @DisplayName("리프레시: 로테이션 후 구 refresh 재사용 → 401 REFRESH_REUSED")
        if (oldRefreshToken.isRevoked())  throw new ApiException(ErrorCode.REFRESH_REVOKED); // @DisplayName("refresh: logout으로 revoke된 refresh로 refresh 시도 → 401 REFRESH_REVOKED")
        if (oldRefreshToken.isExpired(now)) throw new ApiException(ErrorCode.REFRESH_EXPIRED); // @DisplayName("refresh: expires_at 지난 refresh → 401 REFRESH_EXPIRED")

        // 2) user lookup (역추적/정보노출 방지: User가 없으면 REFRESH_INVALID로 뭉개기)
        User user = userRepository.findById(oldRefreshToken.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_INVALID)); // @DisplayName("refresh: 로그인 후 유저 삭제(토큰 row도 함께 제거됨) → REFRESH_INVALID")

        // 3) revoke old token as ROTATED
        oldRefreshToken.touch(now);
        oldRefreshToken.revoke(now, RefreshRevokeReason.ROTATED);

        // 4) issue: new Refresh & Access Token
        boolean rememberMe = oldRefreshToken.isRememberMe();
        Issued newlyIssued = issue(oldRefreshToken.getUserId(), rememberMe); 
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getRole());
        
       /**
         * @DisplayName("로그인: refresh 쿠키 발급 + DB에는 refresh 해시 저장(rememberMe=false)")
         * @DisplayName("로그인: refresh 쿠키 발급 + DB rememberMe=true 저장(rememberMe=true)")
         * @DisplayName("리프레시: 정상 로테이션(새 refresh 발급) + 기존 refresh ROTATED로 폐기 + 새 row는 revoked=false")
         * @DisplayName("리프레시: 로테이션 후 rememberMe 정책 유지(쿠키 TTL + DB rememberMe 유지)")
         */
        return new RotateResult(accessToken, newlyIssued.raw(), rememberMe);
    }

    // 로그아웃/세션 종료 revoke (멱등)
    @Transactional
    public void revokeIfPresent(String refreshRaw, RefreshRevokeReason reason) {
        if (refreshRaw == null || refreshRaw.isBlank()) // @DisplayName("logout: 미발급 쿠키 → 204 (idempotent) + 쿠키 삭제(Max-Age=0)")
            return;

        String hash = TokenHashUtils.sha256Hex(refreshRaw);

        // @DisplayName("logout: refresh 쿠키 있음 → DB 토큰 revoke(LOGOUT) + 쿠키 삭제(Max-Age=0)")
        refreshTokenRepository.findByTokenHashForUpdate(hash).ifPresent(token -> {
            LocalDateTime now = LocalDateTime.now(clock);
            token.touch(now);
            token.revoke(now, reason); // 해당 세션 종료시키기 
        });

        // @DisplayName("logout: 쿠키 없음 → 204 (idempotent) + 쿠키 삭제 헤더는 내려옴")
    }

    private long resolveTtlSeconds(boolean rememberMe) {
        return rememberMe
                ? props.refresh().rememberMeSeconds()
                : props.refresh().sessionTtlSeconds();
    }

    public record Issued(String raw, LocalDateTime expiresAt, boolean rememberMe) {}
    public record RotateResult(String accessToken, String newRefreshRaw, boolean rememberMe) {}
}
