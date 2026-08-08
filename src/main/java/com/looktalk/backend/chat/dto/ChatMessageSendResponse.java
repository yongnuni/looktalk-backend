package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57. CHAT-006 - HOSPITAL/SMS 공통 Success Response.
// ciphertext는 다시 포함하지 않는다. 전송한 메시지를 확인해야 하면 CHAT-005로 조회한다.
public record ChatMessageSendResponse(
        @Schema(description = "생성된 메시지 ID", example = "320")
        Long messageId,

        @Schema(description = "전송 시각")
        Instant createdAt
) {
}
