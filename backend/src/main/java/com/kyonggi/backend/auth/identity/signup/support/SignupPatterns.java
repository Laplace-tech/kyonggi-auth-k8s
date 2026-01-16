package com.kyonggi.backend.auth.identity.signup.support;

public final class SignupPatterns {

    private SignupPatterns() {}

    // ✅ 9~15자, 영문+숫자 포함, 특수문자 1개 이상, 공백 금지
    // - 특수문자는 "영문/숫자/공백"이 아닌 문자로 정의 (리스트 나열 안 해서 안전)
    public static final String PASSWORD_REGEX =
            "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{9,15}$";

    // ✅ 닉네임: 2~20자, 한글/영문/숫자/_ 만
    public static final String NICKNAME_REGEX =
            "^[A-Za-z0-9가-힣_]{2,20}$";
}
