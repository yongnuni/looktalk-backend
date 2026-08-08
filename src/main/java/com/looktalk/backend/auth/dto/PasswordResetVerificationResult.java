package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 18. AUTH-002 PASSWORD_RESET Response
public record PasswordResetVerificationResult(
        @Schema(description = "비밀번호 재설정용 일회성 토큰 원문. AUTH-009 요청에 사용한다.",
                example = "McdwQF7LZoV89CJQZa0yULoQu-eI5A8x7JddM37QD3Q")
        String resetToken
) {
}
