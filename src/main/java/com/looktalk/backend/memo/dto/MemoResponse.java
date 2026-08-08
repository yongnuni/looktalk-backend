package com.looktalk.backend.memo.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 40. MEMO-001
public record MemoResponse(
        @Schema(description = "메모 ID", example = "12")
        Long memoId,

        EncryptedPayload encryptedPayload,

        @Schema(description = "생성 시각")
        Instant createdAt,

        @Schema(description = "최종 수정 시각")
        Instant updatedAt
) {
}
