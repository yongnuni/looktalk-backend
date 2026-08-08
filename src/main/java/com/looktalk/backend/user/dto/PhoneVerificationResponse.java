package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003 Response
public record PhoneVerificationResponse(
        @Schema(description = "인증번호 유효시간(초)", example = "300")
        long expiresInSeconds
) {
}
