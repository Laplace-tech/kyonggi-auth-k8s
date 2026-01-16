package com.kyonggi.backend.auth.identity.me.dto;

import java.util.Objects;

import com.kyonggi.backend.auth.domain.User;

public record MeResponse (
    Long userId,
    String email,
    String nickname,
    String role,
    String status
) {

    /**
     * User 엔티티 -> 응답 DTO 변환 팩토리
     * - 매핑 로직을 한 곳에 모아두면, 필드 변경 시 수정 포인트가 줄어든다.
     */
    public static MeResponse from(User user) {
        Objects.requireNonNull(user, "user must not be null");

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole().name(),
                user.getStatus().name()
        );
    }
}








