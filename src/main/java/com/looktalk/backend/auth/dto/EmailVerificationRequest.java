package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 17. AUTH-001
public record EmailVerificationRequest(
        @Schema(description = "인증할 이메일", example = "patient@example.com")
        @NotBlank @Email @Size(max = 100) String email,

        @Schema(description = "인증 목적", example = "SIGNUP", allowableValues = {"SIGNUP", "PASSWORD_RESET"})
        @NotBlank @Pattern(regexp = "SIGNUP|PASSWORD_RESET") String purpose
) {
}
