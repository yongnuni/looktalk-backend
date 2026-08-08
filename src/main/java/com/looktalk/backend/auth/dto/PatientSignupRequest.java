package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 20. AUTH-004
public record PatientSignupRequest(
        @Schema(description = "로그인 아이디", example = "patient01")
        @NotBlank @Size(max = 50) String loginId,

        @Schema(description = "이메일. SIGNUP 목적으로 이메일 인증이 완료되어 있어야 한다.", example = "patient@example.com")
        @NotBlank @Email @Size(max = 100) String email,

        @Schema(description = "비밀번호 (8~64자)", example = "Password123!")
        @NotBlank @Size(min = 8, max = 64) String password
) {
}
