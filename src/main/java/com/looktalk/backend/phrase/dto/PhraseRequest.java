package com.looktalk.backend.phrase.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 45. PHRASE-002 / 46. PHRASE-003
// 등록과 수정 Request 형식이 동일해 하나의 DTO로 공유한다.
public record PhraseRequest(
        @Schema(description = "분류 (선택, 없으면 null)", example = "null", nullable = true)
        @Size(max = 30) String category,

        @NotNull @Valid EncryptedPayload encryptedPayload
) {
}
