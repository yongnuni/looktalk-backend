package com.looktalk.backend.calibration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 11. CAL-001
// calibrationId/userId/createdAt/revokedAt은 Frontend가 보내지 않는다(Backend가 생성/관리).
public record CalibrationCreateRequest(
        @Schema(description = "Calibration 산출 알고리즘 버전", example = "raw-homography-v1")
        @NotBlank @Size(max = 100) String algorithmVersion,

        @Schema(
                description = "v1 RAW_HOMOGRAPHY + NORMALIZED_VIEWPORT calibration payload. "
                        + "구조는 CalibrationDataValidator가 검증한다.",
                type = "object"
        )
        @NotNull JsonNode calibrationData
) {
}
