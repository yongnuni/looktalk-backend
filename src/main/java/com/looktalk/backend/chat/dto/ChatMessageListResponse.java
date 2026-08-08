package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// docs/LookTalk_Backend_API_Spec_v2.1.md 56. CHAT-005
public record ChatMessageListResponse(
        List<ChatMessageResponse> messages,

        @Schema(description = "다음 페이지(과거 메시지) 존재 여부", example = "true")
        boolean hasNext,

        @Schema(description = "다음 페이지 조회에 사용할 beforeMessageId. hasNext=false면 null", example = "250", nullable = true)
        Long nextCursor
) {
}
