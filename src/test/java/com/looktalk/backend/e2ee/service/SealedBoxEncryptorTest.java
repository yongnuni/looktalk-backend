package com.looktalk.backend.e2ee.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS - SealedBoxEncryptor의 결과가 실제
// libsodium crypto_box_seal과 호환되는지 검증한다. "Base64 문자열이 나온다" 수준이 아니라, 실제
// 공개키/개인키 쌍으로 암호화 → 복호화까지 왕복시켜 원문과 완전히 동일한지 확인한다.
class SealedBoxEncryptorTest {

    @Test
    void encryptedCiphertextIsDecryptableWithRealLibsodiumSealedBoxUsingMatchingPrivateKey() {
        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());

        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        boolean keypairGenerated = lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        assertTrue(keypairGenerated, "libsodium 키 쌍 생성에 실패했습니다.");

        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey);
        String plaintext = "오늘 저녁에 전화해주세요";

        SealedBoxEncryptor encryptor = new SealedBoxEncryptor();
        String ciphertextBase64 = encryptor.encrypt(plaintext, publicKeyBase64);

        byte[] cipher = Base64.getDecoder().decode(ciphertextBase64);
        assertEquals(plaintext.getBytes(StandardCharsets.UTF_8).length + Box.SEALBYTES, cipher.length);

        byte[] decrypted = new byte[cipher.length - Box.SEALBYTES];
        boolean opened = lazySodium.cryptoBoxSealOpen(decrypted, cipher, cipher.length, publicKey, secretKey);

        assertTrue(opened, "실제 libsodium crypto_box_seal_open으로 복호화하지 못했습니다(비호환).");
        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    void differentPlaintextsProduceDifferentCiphertexts() {
        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey);

        SealedBoxEncryptor encryptor = new SealedBoxEncryptor();
        String cipher1 = encryptor.encrypt("hello", publicKeyBase64);
        String cipher2 = encryptor.encrypt("hello", publicKeyBase64);

        // crypto_box_seal은 매번 새 ephemeral keypair를 사용하므로 같은 평문이라도 매번 다른 ciphertext가 나온다.
        assertNotEquals(cipher1, cipher2);
    }
}
