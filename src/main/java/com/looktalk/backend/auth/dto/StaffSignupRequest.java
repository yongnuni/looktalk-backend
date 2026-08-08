package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 21. AUTH-005
public record StaffSignupRequest(
        @Schema(description = "로그인 아이디", example = "doctor01")
        @NotBlank @Size(max = 50) String loginId,

        @Schema(description = "이메일. SIGNUP 목적으로 이메일 인증이 완료되어 있어야 한다.", example = "doctor@hospital.com")
        @NotBlank @Email @Size(max = 100) String email,

        @Schema(description = "비밀번호 (8~64자)", example = "Password123!")
        @NotBlank @Size(min = 8, max = 64) String password,

        @Schema(description = "병원 초대코드", example = "SEOUL2026")
        @NotBlank String inviteCode
) {
}
