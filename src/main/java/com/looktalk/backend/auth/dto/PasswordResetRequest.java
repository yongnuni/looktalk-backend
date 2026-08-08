package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 25. AUTH-009
public record PasswordResetRequest(
        @Schema(description = "AUTH-002(PASSWORD_RESET) 응답으로 받은 resetToken",
                example = "McdwQF7LZoV89CJQZa0yULoQu-eI5A8x7JddM37QD3Q")
        @NotBlank String resetToken,

        @Schema(description = "새 비밀번호 (8~64자)", example = "NewPassword456!")
        @NotBlank @Size(min = 8, max = 64) String newPassword
) {
}
