package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// docs/LookTalk_Backend_API_Spec_v2.1.md 54. CHAT-003
public record ChatHospitalRoomRequest(
        @Schema(description = "채팅할 내부 회원 ID", example = "U100")
        @NotBlank String targetUserId
) {
}
