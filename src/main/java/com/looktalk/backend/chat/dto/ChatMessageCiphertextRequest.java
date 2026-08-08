package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.1 CHAT-006 HOSPITAL - ciphertexts[] 항목 하나.
public record ChatMessageCiphertextRequest(
        @Schema(description = "이 ciphertext를 복호화할 수 있는 내부 회원 ID", example = "U001")
        @NotBlank String recipientUserId,

        @Schema(description = "암호화에 사용한 recipient의 현재 active E2EE 공개키 버전", example = "1")
        @NotNull @Min(1) Integer keyVersion,

        @Schema(description = "암호화 알고리즘. 현재 LIBSODIUM_SEALED_BOX만 허용한다.", example = "LIBSODIUM_SEALED_BOX")
        @NotBlank String algorithm,

        @Schema(description = "RFC 4648 Standard Base64로 인코딩된 암호문", example = "BASE64_FOR_U001")
        @NotBlank @Size(max = 20000) String ciphertext
) {
}
