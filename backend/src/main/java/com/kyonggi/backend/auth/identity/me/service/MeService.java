package com.kyonggi.backend.auth.identity.me.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kyonggi.backend.auth.domain.User;
import com.kyonggi.backend.auth.domain.UserStatus;
import com.kyonggi.backend.auth.identity.me.dto.MeResponse;
import com.kyonggi.backend.auth.repo.UserRepository;
import com.kyonggi.backend.global.ApiException;
import com.kyonggi.backend.global.ErrorCode;
import com.kyonggi.backend.security.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/** 
 * 내 정보 조회 유스케이스
 *
 * 정책:
 * - 인증이 없으면 AUTH_REQUIRED
 * - 토큰은 유효하지만 사용자 없음 -> USER_NOT_FOUND (비정상 상태)
 * - 계정 상태가 ACTIVE가 아니면 -> ACCOUNT_DISABLED
 */
@Service
@RequiredArgsConstructor
public class MeService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public MeResponse me(AuthPrincipal principal) {
        Long userId = requireUserId(principal);
        User user = loadUserOrThrow(userId);  // @DisplayName("me: 토큰은 유효하지만 DB에 유저 없음 → USER_NOT_FOUND") 
        ensureActive(user);                   // @DisplayName("me: 토큰은 유효하지만 비활성 계정 → ACCOUNT_DISABLED")

        return MeResponse.from(user);
    }

    /**
     * SecurityConfig의 RestAuthenticationEntryPointer에서 막아야 하지만
     * 서비스 계층에서도 한 번 더 방어하여 안전성을 높인다.
     */
    private Long requireUserId(AuthPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return principal.userId();
    }

    private User loadUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

}
