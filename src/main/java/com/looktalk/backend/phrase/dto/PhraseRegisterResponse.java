package com.looktalk.backend.phrase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 45. PHRASE-002 Response
public record PhraseRegisterResponse(
        @Schema(description = "새로 등록된 문장 ID", example = "10")
        Long phraseId,

        @Schema(description = "FIFO로 교체되어 삭제된 문장 ID. 교체된 문장이 없으면 null.", example = "3", nullable = true)
        Long replacedPhraseId,

        @Schema(description = "생성 시각")
        Instant createdAt
) {
}
