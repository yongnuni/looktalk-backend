package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 26. USER-001
public record UserMeResponse(
        @Schema(description = "내부 사용자 ID", example = "U001")
        String userId,

        @Schema(description = "로그인 아이디", example = "patient01")
        String loginId,

        @Schema(description = "이메일", example = "patient@example.com")
        String email,

        @Schema(description = "마이페이지에서 설정한 이름 (없으면 null)", example = "null", nullable = true)
        String name,

        @Schema(description = "화면 표시용 이름. name이 있으면 name, 없으면 loginId", example = "patient01")
        String displayName,

        @Schema(description = "인증 완료된 전화번호 (없으면 null)", example = "null", nullable = true)
        String phone,

        @Schema(description = "이메일 인증 완료 여부", example = "true")
        boolean emailVerified,

        @Schema(description = "전화번호 인증 완료 여부", example = "false")
        boolean smsVerified,

        @Schema(description = "사용자 역할", example = "PATIENT")
        String role
) {
}
