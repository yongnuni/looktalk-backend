package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 19. AUTH-003
public record LoginIdAvailabilityResponse(
        @Schema(description = "사용 가능 여부 (true=사용 가능, false=이미 사용 중)", example = "true")
        boolean available
) {
}
