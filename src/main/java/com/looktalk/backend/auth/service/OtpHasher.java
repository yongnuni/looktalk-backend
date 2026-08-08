package com.looktalk.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 5.5 - 인증번호는 서버 비밀키로 HMAC-SHA256 해시만 저장한다.
// JWT secret과는 별도의 OTP 전용 secret을 사용한다.
@Component
public class OtpHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public OtpHasher(@Value("${looktalk.otp.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String hash(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OTP 해시 계산에 실패했습니다.", exception);
        }
    }

    public boolean matches(String code, String expectedHash) {
        return MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
