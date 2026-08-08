package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// docs/LookTalk_Backend_API_Spec_v2.1.md 52. CHAT-001
public record ChatContactListResponse(
        List<ChatContactResponse> content,

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        boolean hasNext
) {
}
