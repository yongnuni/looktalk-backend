package com.looktalk.backend.chat.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket MESSAGE_CREATED Event의 data.
// encryptedPayload는 수신자(recipient) 본인의 ciphertext만 담는다 - 다른 recipient의 ciphertext는 이 객체에
// 절대 포함되지 않는다(recipient마다 별도 Event를 만들어 각자에게 개인적으로 전달한다).
public record ChatMessageCreatedEventData(
        @Schema(description = "메시지 ID", example = "320")
        Long messageId,

        @Schema(description = "보낸 participant ID", example = "5")
        Long senderParticipantId,

        @Schema(description = "보낸 회원 ID (CHAT-006 sender는 항상 현재 인증된 내부 사용자다)", example = "U100")
        String senderUserId,

        @Schema(description = "보낸 사람 표시 이름", example = "김철수")
        String senderDisplayName,

        @Schema(description = "수신자 본인용 ciphertext")
        EncryptedPayload encryptedPayload,

        @Schema(description = "전송 시각")
        Instant createdAt
) {
}
