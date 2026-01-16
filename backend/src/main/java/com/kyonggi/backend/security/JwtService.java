package com.kyonggi.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;
 
import com.kyonggi.backend.auth.config.AuthProperties;
import com.kyonggi.backend.auth.domain.UserRole; 

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * Access Token(JWT) 발급/검증 서비스
 * 
 * - HTTP(상태코드/응답)는 모른다. "유효/무효"만 판단한다.
 * - 서버가 매번 DB를 조회하지 않고도(Stateless) 서명 검증만으로 "내가 발급한 토큰"인지 확인 가능
 * - 검증 실패는 InvalidJwtException(런타임)으로 통일해서 던지고, 필터/엔트리포인트가 401 ApiError로 변환한다.
 * 
 * 기능:
 * - 발급: issueAccessToken(userId, role): 
 *      JWT 생성 및 발급 (header/payload/signature를 jjwt가 알아서 만들어줌)
 * - 검증: verifyAccessToken(token): 
 *      JWT에서 서명/만료/issuer를 검증 후 AuthPrincipal로 복원
 * 
 * 
 * Access Token:
 * - 매 요청마다 서버에게 내 신원을 증명하는 짧은 수명 토큰
 * - 보통 프론트는 Authorization: Bearer <accessToken> 헤더에 담아 보냄
 * 
 * JWT 구조: header.payload.signature
 * - header: 알고리즘/타입 정보 (HS256, JWT)
 * - payload: 유저 정보(클레임: iss/sub/role/iat/exp 등)
 * - signature: header.payload를 서버 비밀키로 서명한 값(HMAC-SHA256)
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String ROLE_CLAIM = "role";

    private final AuthProperties.Jwt jwtProps;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;


    public JwtService(AuthProperties props, Clock clock) {
        this.jwtProps = props.jwt();
        this.clock = clock;

        // secret length 검증 + 키 생성
        this.key = buildHmacKey(jwtProps.secret()); 

        // Parser 빌딩: 향후 verifyAccessToken()에서 지금 넘겨진 jwtProps.issuer와 key로 검증
        // - issuer(iss) 고정(requireIssuer)로 타 서비스 토큰을 차단한다.
        this.parser = buildParser(jwtProps.issuer(), this.key, this.clock);
    }


    /** userId/role 기반 Access JWT 발급 */
    public String issueAccessToken(Long userId, UserRole role) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        if (role == null) throw new IllegalArgumentException("role must not be null");

        Instant now = clock.instant();
        Instant exp = now.plusSeconds(jwtProps.accessTtlSeconds());
        
        return Jwts.builder()
                .setIssuer(jwtProps.issuer())                // iss
                .setSubject(String.valueOf(userId))          // sub
                .claim(ROLE_CLAIM, role.name())              // role: "USER"
                .setIssuedAt(Date.from(now))                 // iat
                .setExpiration(Date.from(exp))               // exp
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();               
    }
    
    /**
     * Access Token 검증 후, AuthPrincipal 반환
     * 
     * 실패 시 InvalidJwtException을 던진다.
     * - HTTP 레벨 처리는 Filter/EntryPoint가 담당 (서비스는 HTTP 몰라야 깔끔)
     */
    public AuthPrincipal verifyAccessToken(String token) {
        try {
            if (token == null || token.isBlank()) {
                throw new JwtException("token is null or blank");
            }

            // 서명/만료/issuer/포맷 검증 (하나라도 실패하면 JwtException)
            Jws<Claims> jws = parser.parseClaimsJws(token);
            Claims claims = jws.getBody();

            // 클레임에서 userId, role 꺼내기
            Long userId = parseUserId(claims.getSubject());
            UserRole role = parseRole(claims.get(ROLE_CLAIM, String.class));

            return new AuthPrincipal(userId, role);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidJwtException("Invalid JWT", e);
        }
    }


    private static SecretKey buildHmacKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank");
        }

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }

        return Keys.hmacShaKeyFor(bytes);
    }

    private static JwtParser buildParser(String issuer, SecretKey key, Clock clock) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("JWT issuer must not be blank");
        }

        return Jwts.parserBuilder()
                .requireIssuer(issuer)
                .setSigningKey(key)
                // JJWT는 Date 기반 clock을 쓰므로 여기서 bridge
                .setClock(() -> Date.from(clock.instant()))
                .build();
    }

    // subject:userId -> Long userId 파싱
    private static Long parseUserId(String sub) {
        if (sub == null || sub.isBlank()) {
            throw new JwtException("subject (userId) is missing");
        }
        try {
            return Long.valueOf(sub);
        } catch (NumberFormatException e) {
            throw new JwtException("subject is not a valid Long: " + sub, e);
        }
    }

    /**
     * role claim을 안전하게 enum으로 파싱한다.
     * - "USER" / "ADMIN" 형태 기대
     * - 혹시 "ROLE_USER"로 들어와도 방어적으로 처리
     */
    private static UserRole parseRole(String roleRaw) {
        if (roleRaw == null || roleRaw.isBlank()) {
            throw new JwtException("role claim missing");
        }

        String normalized = roleRaw.startsWith("ROLE_")
                ? roleRaw.substring("ROLE_".length())
                : roleRaw;

        try {
            return UserRole.valueOf(normalized);
        } catch (Exception e) {
            throw new JwtException("role claim invalid: " + roleRaw, e);
        }
    }

    /**
     * HTTP 레벨과 분리된 “JWT 검증 실패” 도메인 예외
     * - Filter에서 잡아서 401 ApiError로 변환한다.
     */
    public static class InvalidJwtException extends RuntimeException {
        public InvalidJwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}


    // /**
    //  * =================
    //  * Access Token 발급
    //  * =================
    //  * 
    //  * 입력: userId, role
    //  * 출력: JWT 문자열(header.payload.signature)
    //  * 
    //  * 발급 흐름
    //  * - header JSON 생성 ("alg": "HS256", "typ": "JWT")
    //  * - payload JSON 생성 (iat/exp 포함)
    //  * - Base64URL 인코딩 -> "header.payload" 서명 (HMAC-SHA256)
    //  * - signature까지 붙여서 반환
    //  */
    // public String issueAccessToken(Long userId, String role) {
    //     try {
    //         // 1) JWT header: 어떤 알고리즘으로 서명했는지 + 타입 {"alg": "HS256", "typ": "JWT"}
    //         String headerJson = om.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
            
    //         // 2) issued_at/expire: epoch seconds(초 단위)
    //         long now = Instant.now(clock).getEpochSecond();
    //         long exp = now + jwtProps.accessTtlSeconds();

    //         /**
    //          * 3) JWT payload(클레임): "이 토큰은 누구 것인지 판단"
    //          * - iss (issue): 발급자(우리 서비스)
    //          * - sub: 유저 식별자(여기선 userId)
    //          * - role: 권한(인가에 사용)
    //          * - iat(issue_at)/exp(expire): 발급/만료
    //          */
    //         String payloadJson = om.writeValueAsString(Map.of(
    //                 "iss", jwtProps.issuer(),
    //                 "sub", String.valueOf(userId),
    //                 "role", role,
    //                 "iat", now,
    //                 "exp", exp
    //         ));

    //         // 4) header/payload를 Base64URL 인코딩
    //         String header = b64Url(headerJson.getBytes(StandardCharsets.UTF_8));
    //         String payload = b64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
    //         String signingInput = header + "." + payload; // 서명 대상 문자열

    //         // 5) 서명 생성(HMAC-SHA256) -> Base64URL 인코딩 -> signature
    //         String sig = b64Url(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8)));

    //         // 6) 최종 JWT 반환 (header.payload.signature)
    //         return signingInput + "." + sig;
    //     } catch (Exception e) {
    //         throw new IllegalStateException("Failed to issue JWT", e);
    //     }
    // }
    //     // HMAC-SHA256 서명 생성 (서버 비밀키 사용)
    // private byte[] hmacSha256(byte[] data) throws Exception {
    //     Mac mac = Mac.getInstance("HmacSHA256");
    //     mac.init(this.key); // 생성자에서 만든 SecretKey 재사용
    //     return mac.doFinal(data);
    // }

    // // Base64 URL-safe 인코딩 (JWT 표준) 
    // private String b64Url(byte[] bytes) {
    //     return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    // }