package com.jjinmak.member.app;

/**
 * 소셜 로그인 결과.
 * 연결된 회원이 있으면 authResult, 처음 로그인한 사용자면 가입 대기 토큰(signupToken)과
 * 제공자가 인증한 이메일(email, 없으면 null)을 가진다.
 */
public record SocialLoginResult(
        AuthResult authResult,
        String signupToken,
        String email
) {
    public static SocialLoginResult loggedIn(AuthResult authResult) {
        return new SocialLoginResult(authResult, null, null);
    }

    public static SocialLoginResult signupRequired(String signupToken, String email) {
        return new SocialLoginResult(null, signupToken, email);
    }

    public boolean isSignupRequired() {
        return signupToken != null;
    }
}
