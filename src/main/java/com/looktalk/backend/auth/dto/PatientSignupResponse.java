package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 20. AUTH-004 Success
public record PatientSignupResponse(
        @Schema(description = "서버에서 생성한 내부 사용자 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        String userId,

        @Schema(description = "로그인 아이디", example = "patient01")
        String loginId,

        @Schema(description = "사용자 역할", example = "PATIENT")
        String role
) {
}
