package com.kyonggi.backend.auth.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.kyonggi.backend.auth.domain.EmailOtp;
import com.kyonggi.backend.auth.domain.OtpPurpose;

import jakarta.persistence.LockModeType;

@Repository
public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    Optional<EmailOtp> findByEmailAndPurpose(String email, OtpPurpose purpose);


    /**
     * Row-lock 조회 (대부분 DB에서 SELECT ... FOR UPDATE).
     *
     * 동일 (email, purpose) OTP 흐름을 트랜잭션 단위로 직렬화한다.
     * - request: 쿨다운/일일제한 체크 레이스 방지    (동시에 OTP 발급 요청)
     * - verify: 실패 횟수 누적(lost update) 방지   (동시에 OTP 인증 요청)
     * - complete: OTP 1회 소비(one-time use) 보장 (동시에 회원가입 요청)
     *
     * 주의: row가 없으면 잠글 대상이 없다.
     * → 최초 생성 레이스는 (email, purpose) UNIQUE 제약으로 막고,
     *   insert 충돌(DataIntegrityViolationException) 시 재조회 후 정책 검증/갱신으로 처리한다.
     *
     * @Transactional 안에서 호출되어야 락이 유지된다.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmailOtp e where e.email = :email and e.purpose = :purpose")
    Optional<EmailOtp> findByEmailAndPurposeForUpdate(
            @Param("email") String email,
            @Param("purpose") OtpPurpose purpose);
}
