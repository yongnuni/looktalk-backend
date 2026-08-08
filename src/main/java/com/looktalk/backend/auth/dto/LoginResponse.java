package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 22. AUTH-006 Response
// Access Token은 이 Response Body로 반환한다. Refresh Token은 Body에 노출하지 않고
// LOOKTALK_REFRESH HttpOnly Cookie로만 전달한다 (8. Request Header 및 Cookie).
public record LoginResponse(
        @Schema(description = "Access Token. Authorization: Bearer {accessToken} 형식으로 사용한다.",
                example = "eyJhbGciOiJIUzUxMiJ9...")
        String accessToken,

        @Schema(description = "Token 타입", example = "Bearer")
        String tokenType,

        @Schema(description = "Access Token 만료시간(초)", example = "900")
        long expiresIn,

        @Schema(description = "사용자 역할", example = "PATIENT")
        String role
) {
}
