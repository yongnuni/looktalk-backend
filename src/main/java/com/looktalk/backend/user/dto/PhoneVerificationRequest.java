package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003
public record PhoneVerificationRequest(
        @Schema(description = "인증할 전화번호 (서버에서 정규화한다)", example = "01012345678")
        @NotBlank @Size(max = 20) String phone
) {
}
