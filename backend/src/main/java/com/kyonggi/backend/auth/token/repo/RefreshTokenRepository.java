package com.kyonggi.backend.auth.token.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.kyonggi.backend.auth.token.domain.RefreshToken;

import jakarta.persistence.LockModeType;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * rotate 동시성 방어용 Row Lock 조회.
     *
     * - PESSIMISTIC_WRITE = (대부분 DB에서) SELECT ... FOR UPDATE
     * - 같은 token_hash row에 대해 "동시에 두 rotate가 성공"하는 것을 막기 위해 사용한다.
     * - 두 요청이 동시에 들어오면, 한 트랜잭션이 row를 잠근 동안 다른 트랜잭션은 대기한다.
     * - 먼저 끝난 쪽이 old 토큰을 ROTATED로 바꾸면, 대기하던 쪽은 깨어난 뒤 ROTATED 상태를 보고 REFRESH_REUSED로 차단된다.
     *
     * 주의:
     * - '읽기 자체를 전부 막는다'가 아니라, '해당 row를 업데이트/잠금 조회하는 작업'을 직렬화하는 게 핵심이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
