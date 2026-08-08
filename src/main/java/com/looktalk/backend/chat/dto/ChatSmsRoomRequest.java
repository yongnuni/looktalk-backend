package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// docs/LookTalk_Backend_API_Spec_v2.1.md 55. CHAT-004
public record ChatSmsRoomRequest(
        @Schema(description = "SMS 채팅할 friendship ID", example = "7")
        @NotNull Long friendshipId
) {
}
