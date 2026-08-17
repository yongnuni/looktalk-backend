package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6. EMERGENCY-001
public record EmergencyCallCreateResponse(
        @Schema(description = "생성된 긴급 호출 ID", example = "10")
        Long emergencyCallId,

        @Schema(description = "호출 발생 시각(Backend 시각)", example = "2026-08-17T00:00:00Z")
        Instant calledAt
) {
}
