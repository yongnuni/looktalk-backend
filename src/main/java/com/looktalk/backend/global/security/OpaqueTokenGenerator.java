package com.looktalk.backend.global.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

// Refresh Token / Password Reset Token 원문 생성.
// JWT가 아닌 SecureRandom 기반 opaque random token을 사용한다.
@Component
public class OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
