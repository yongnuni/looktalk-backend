
package com.looktalk.backend.inputsession.dto;

import com.looktalk.backend.user.entity.InputMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record InputSessionResponse(

        @Schema(description = "측정 로그 ID")
        String logId,

        @Schema(description = "연결된 Calibration ID")
        String calibrationId,

        @Schema(description = "입력 방식")
        InputMethod inputMethod,

        @Schema(description = "세션 유형")
        String sessionType,

        @Schema(description = "키보드 레이아웃")
        String keyboardLayout,

        @Schema(description = "오타율 (0~1)")
        Float typoRate,

        @Schema(description = "분당 입력 속도")
        Float inputSpeed,

        @Schema(description = "인식 정확도 (0~1)")
        Float recognitionAccuracy,

        @Schema(description = "입력 안정성 (0~1)")
        Float inputStability,

        @Schema(description = "측정 시각")
        Instant sessionAt
) {
}
