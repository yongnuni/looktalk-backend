package com.looktalk.backend.chat.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 56. CHAT-005
public record ChatMessageResponse(
        @Schema(description = "메시지 ID", example = "299")
        Long messageId,

        @Schema(description = "보낸 participant ID", example = "4")
        Long senderParticipantId,

        @Schema(description = "보낸 회원 ID (탈퇴/외부 SMS면 null)", example = "U100", nullable = true)
        String senderUserId,

        @Schema(description = "보낸 사람 표시 이름 (탈퇴한 사용자면 '탈퇴한 사용자', 외부 SMS면 snapshot 이름)", example = "김철수")
        String senderDisplayName,

        @Schema(description = "현재 사용자용 ciphertext")
        EncryptedPayload encryptedPayload,

        @Schema(description = "메시지 종류", example = "TEXT")
        String messageType,

        @Schema(description = "전송 시각")
        Instant createdAt
) {
}
