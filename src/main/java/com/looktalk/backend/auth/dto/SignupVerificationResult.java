package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 18. AUTH-002 SIGNUP Response
public record SignupVerificationResult(
        @Schema(description = "이메일 인증 완료 여부", example = "true")
        boolean verified
) {
}
