package com.kyonggi.backend.auth.token.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 토큰 해시 유틸
 * 
 * - refresh token "원문"이 DB에 저장되면 유출 시 바로 악용 가능
 * - 그래서 DB에는 "해시(token_hash)"만 저장하고 실제 비교는 
 *   : incoming raw token -> (sha256Hex) -> DB token_hash와 비교
 */
public final class TokenHashUtils {
    private TokenHashUtils() {}

    /**
     * raw 문자열을 SHA-256 해시 후 hex(64 chars) 문자열로 반환
     */
    public static String sha256Hex(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("raw token must not be null/blank");
        }

        byte[] digest = sha256(raw.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256이 없으면 JVM/환경 자체가 비정상에 가깝다.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 바이트 배열을 소문자 hex 문자열로 변환
     * - String.format 루프보다 빠르고 GC 부담이 적다.
     */
    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();

        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }
}