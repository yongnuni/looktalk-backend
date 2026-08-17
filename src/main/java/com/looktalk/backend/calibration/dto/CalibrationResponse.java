package com.looktalk.backend.calibration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 11. CAL-001/CAL-002 Response
public record CalibrationResponse(
        @Schema(description = "Backend가 생성한 Calibration ID(UUID)", example = "3f7c1e2a-4b9d-4e3a-9c1f-2f6b8a9c3d5e")
        String calibrationId,

        @Schema(description = "Calibration 산출 알고리즘 버전", example = "raw-homography-v1")
        String algorithmVersion,

        @Schema(description = "저장된 v1 calibration payload", type = "object")
        JsonNode calibrationData,

        @Schema(description = "생성 시각")
        Instant createdAt
) {
}
