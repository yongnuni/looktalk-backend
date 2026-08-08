package com.looktalk.backend.e2ee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 6, 6.1, 64. 공통 암호화 Payload
// ciphertext는 RFC 4648 Standard Base64로 인코딩한다 (URL-safe Base64 사용 금지).
public record EncryptedPayload(
        @Schema(description = "암호화 알고리즘. 현재 LIBSODIUM_SEALED_BOX만 허용한다.", example = "LIBSODIUM_SEALED_BOX")
        @NotBlank String algorithm,

        @Schema(description = "암호화에 사용한 현재 사용자의 E2EE 공개키 버전", example = "1")
        @NotNull @Min(1) Integer keyVersion,

        @Schema(description = "RFC 4648 Standard Base64로 인코딩된 암호문", example = "BASE64_CIPHERTEXT")
        @NotBlank @Size(max = 20000) String ciphertext
) {
}
