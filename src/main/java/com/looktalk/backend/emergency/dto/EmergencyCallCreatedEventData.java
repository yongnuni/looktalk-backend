package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket - EMERGENCY_CALL_CREATED Event의 data.
// Emergency 팝업에 필요한 최소 식별 정보만 담는다. 환자 이메일/전화번호/토큰/E2EE key 등은 포함하지 않는다.
public record EmergencyCallCreatedEventData(
        @Schema(description = "긴급 호출 ID", example = "10")
        Long emergencyCallId,

        @Schema(description = "호출한 환자의 내부 사용자 ID", example = "P001")
        String patientUserId,

        @Schema(description = "환자 표시명 (userName -> loginId)", example = "김철수")
        String patientDisplayName,

        @Schema(description = "호출 발생 시각")
        Instant calledAt
) {
}
