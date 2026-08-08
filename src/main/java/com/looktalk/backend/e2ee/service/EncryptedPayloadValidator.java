package com.looktalk.backend.e2ee.service;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import java.util.Base64;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 6, 64. E2EE 처리 방식 / E2EE DTO 처리
// Memo, Phrase(그리고 향후 병원 채팅)에서 공통으로 사용하는 암호화 Payload 형식 검증.
// Backend는 ciphertext를 복호화하지 않고 형식만 확인한다.
@Component
public class EncryptedPayloadValidator {

    public static final String LIBSODIUM_SEALED_BOX = "LIBSODIUM_SEALED_BOX";

    public void validate(EncryptedPayload payload) {
        if (!LIBSODIUM_SEALED_BOX.equals(payload.algorithm())) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }

        try {
            Base64.getDecoder().decode(payload.ciphertext());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }
    }
}
