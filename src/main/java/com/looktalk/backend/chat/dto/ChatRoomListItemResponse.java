package com.looktalk.backend.chat.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53. CHAT-002
public record ChatRoomListItemResponse(
        @Schema(description = "채팅방 ID", example = "10")
        Long roomId,

        @Schema(description = "채팅방 종류", example = "HOSPITAL")
        String roomType,

        ChatRoomTargetResponse target,

        @Schema(description = "마지막 메시지 ciphertext (메시지가 없으면 null)", nullable = true)
        EncryptedPayload lastMessageEncryptedPayload,

        @Schema(description = "마지막 메시지 시각 (메시지가 없으면 null)", nullable = true)
        Instant lastMessageAt
) {
}
