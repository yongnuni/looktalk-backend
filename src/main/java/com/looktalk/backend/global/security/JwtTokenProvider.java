package com.looktalk.backend.global.security;

import com.looktalk.backend.user.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 9. 인증 구조
// Access Token에만 JWT를 사용한다. sub=userId, role claim을 담는다.
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final long accessTokenExpirySeconds;

    public JwtTokenProvider(
            @Value("${looktalk.jwt.secret}") String secret,
            @Value("${looktalk.jwt.access-token-expiry-seconds}") long accessTokenExpirySeconds
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirySeconds = accessTokenExpirySeconds;
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpirySeconds;
    }

    public String generateAccessToken(String userId, UserRole role) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenExpirySeconds)))
                .signWith(key)
                .compact();
    }

    // 만료(ExpiredJwtException)와 그 외 위조/형식 오류(JwtException)를 호출부에서 구분할 수 있도록 그대로 던진다.
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
