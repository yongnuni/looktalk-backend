package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57. CHAT-006
// HOSPITAL/SMS가 하나의 endpoint와 Request 구조를 공유한다. roomType별 필드 사용 규칙(ciphertexts는 HOSPITAL
// 전용, content는 SMS 전용)은 Bean Validation이 아니라 ChatMessageService의 business validation으로 강제한다.
public record ChatSendMessageRequest(
        @Schema(description = "메시지 종류. 현재 TEXT만 지원한다.", example = "TEXT")
        @NotBlank String messageType,

        @Schema(description = "HOSPITAL room 전용. recipient별 ciphertext 목록(SMS room에서는 사용하지 않는다).",
                nullable = true)
        @Valid List<ChatMessageCiphertextRequest> ciphertexts,

        @Schema(description = "SMS room 전용 평문 메시지(HOSPITAL room에서는 사용하지 않는다).",
                example = "오늘 저녁에 전화해주세요", nullable = true)
        String content
) {
}
