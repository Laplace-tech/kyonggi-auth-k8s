package com.kyonggi.backend.auth.token.domain;

/**
 * RefreshToken 폐기(Revoke) 사유
 * 
 * RefreshToken은 서버가 세션처럼 통제하는 상태(State)
 * 
 * ROTATED: 정상적인 로테이션으로 이전 토큰을 폐기함 (이미 ROTATED 된 토큰으로 제출 시 재사용 공격으로 간주)
 * LOGOUT: 사용자가 명시적으로 로그아웃하여 서버가 세션을 종료할 때
 */
public enum RefreshRevokeReason { ROTATED, LOGOUT }
