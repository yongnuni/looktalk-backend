package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 29. USER-004
public record PhoneVerificationConfirmRequest(
        @Schema(description = "인증할 전화번호", example = "01012345678")
        @NotBlank @Size(max = 20) String phone,

        @Schema(description = "SMS로 발송된 6자리 인증번호", example = "391827")
        @NotBlank @Pattern(regexp = "\\d{6}") String code
) {
}
