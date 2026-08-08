package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 22. AUTH-006
public record LoginRequest(
        @Schema(description = "로그인 아이디", example = "patient01")
        @NotBlank @Size(max = 50) String loginId,

        @Schema(description = "비밀번호", example = "Password123!")
        @NotBlank String password
) {
}
