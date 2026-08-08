package com.looktalk.backend.phrase.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 44. PHRASE-001
public record PhraseResponse(
        @Schema(description = "문장 ID", example = "10")
        Long phraseId,

        @Schema(description = "분류 (없으면 null)", example = "null", nullable = true)
        String category,

        EncryptedPayload encryptedPayload,

        @Schema(description = "생성 시각")
        Instant createdAt
) {
}
