package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6. EMERGENCY-002
// patientDisplayName은 이번 Step에서는 기존 userName -> loginId 규칙을 사용한다.
// patient_alias 반영은 V9 이후 별도 Step에서 처리한다.
public record EmergencyCallListItemResponse(
        @Schema(description = "긴급 호출 ID", example = "10")
        Long emergencyCallId,

        @Schema(description = "호출한 환자의 내부 사용자 ID", example = "P001")
        String patientUserId,

        @Schema(description = "환자 표시명 (userName -> loginId)", example = "김철수")
        String patientDisplayName,

        @Schema(description = "호출 발생 시각", example = "2026-08-17T00:00:00Z")
        Instant calledAt,

        @Schema(description = "읽음 처리 시각. 아직 읽지 않았으면 null", nullable = true)
        Instant readAt,

        @Schema(description = "최초로 읽음 처리한 STAFF ID. 아직 읽지 않았으면 null", nullable = true)
        Long readByStaffId,

        @Schema(description = "읽지 않음 여부", example = "true")
        boolean unread
) {
}
