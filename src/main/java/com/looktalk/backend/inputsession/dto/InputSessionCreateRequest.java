
package com.looktalk.backend.inputsession.dto;

import com.looktalk.backend.user.entity.InputMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InputSessionCreateRequest(

        @Schema(description = "세션 유형", example = "CALIBRATION_TEST")
        @NotBlank
        @Pattern(regexp = "CALIBRATION_TEST|NORMAL_USE")
        String sessionType,

        @Schema(description = "입력 방식", example = "MOUTH")
        @NotNull
        InputMethod inputMethod,

        @Schema(description = "연결된 Calibration ID", example = "3f7c1e2a-4b9d-4e3a-9c1f-2f6b8a9c3d5e")
        String calibrationId,

        @Schema(description = "키보드 레이아웃", example = "QWERTY")
        @Size(max = 20)
        String keyboardLayout,

        @Schema(description = "오타율 (0~1)", example = "0.0")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        Float typoRate,

        @Schema(description = "분당 입력 속도", example = "2.6374")
        @DecimalMin("0.0")
        Float inputSpeed,

        @Schema(description = "인식 정확도 (0~1)", example = "0.95")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        Float recognitionAccuracy,

        @Schema(description = "입력 안정성 (0~1)", example = "0.90")
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        Float inputStability
) {
}
