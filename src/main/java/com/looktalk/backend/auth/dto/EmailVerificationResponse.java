package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 17. AUTH-001 Success
public record EmailVerificationResponse(
        @Schema(description = "인증번호 유효시간(초)", example = "300")
        long expiresInSeconds
) {
}
