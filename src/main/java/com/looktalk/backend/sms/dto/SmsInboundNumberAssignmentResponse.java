package com.looktalk.backend.sms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record SmsInboundNumberAssignmentResponse(
        @Schema(description = "assignment ID", example = "1")
        Long assignmentId,

        @Schema(description = "환자 내부 사용자 ID", example = "P001")
        String userId,

        @Schema(description = "배정된 서비스 번호", example = "07012345678")
        String serviceNumber,

        @Schema(description = "현재 active 여부", example = "true")
        boolean active,

        @Schema(description = "배정 시각")
        Instant activatedAt
) {
}
