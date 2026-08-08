package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 23. AUTH-007 Response
public record TokenRefreshResponse(
        @Schema(description = "새로 발급된 Access Token", example = "eyJhbGciOiJIUzUxMiJ9...")
        String accessToken,

        @Schema(description = "Token 타입", example = "Bearer")
        String tokenType,

        @Schema(description = "Access Token 만료시간(초)", example = "900")
        long expiresIn
) {
}
