package com.looktalk.backend.e2ee.service;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS - libsodium crypto_box_seal 호환 암호화.
// 직접 구현한 X25519/XSalsa20-Poly1305 재현이 아니라 실제 libsodium 네이티브 라이브러리(lazysodium-java가
// JNA로 바인딩)를 그대로 호출해 알고리즘 비호환 위험을 없앤다. 복호화는 Frontend가 개인키로 수행하므로
// Backend에는 seal(암호화)만 있고 개인키를 다루는 unseal(복호화) 로직은 없다.
@Component
public class SealedBoxEncryptor {

    private final LazySodiumJava lazySodium;

    public SealedBoxEncryptor() {
        this.lazySodium = new LazySodiumJava(new SodiumJava());
    }

    // publicKeyBase64: 기존 E2EE 공개키 등록(E2EE-001)에서 검증된 Standard Base64 32바이트 공개키.
    // 반환값: RFC 4648 Standard Base64로 인코딩된 crypto_box_seal 결과
    // (ephemeral public key 32B + Poly1305 MAC 16B + 암호문).
    public String encrypt(String plaintext, String publicKeyBase64) {
        byte[] publicKey = Base64.getDecoder().decode(publicKeyBase64);
        byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipher = new byte[message.length + Box.SEALBYTES];

        boolean success = lazySodium.cryptoBoxSeal(cipher, message, message.length, publicKey);
        if (!success) {
            throw new IllegalStateException("libsodium crypto_box_seal 암호화에 실패했습니다.");
        }

        return Base64.getEncoder().encodeToString(cipher);
    }
}
