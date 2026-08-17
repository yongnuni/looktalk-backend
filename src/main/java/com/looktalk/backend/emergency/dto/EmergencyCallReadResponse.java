package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6. EMERGENCY-003
// 최초 reader의 read_at/read_by_staff_id를 반환한다. 이후 요청도 동일한(변경되지 않은) 값을 반환한다.
public record EmergencyCallReadResponse(
        @Schema(description = "긴급 호출 ID", example = "10")
        Long emergencyCallId,

        @Schema(description = "읽음 처리 시각(최초 reader 기준)", example = "2026-08-17T00:01:00Z")
        Instant readAt,

        @Schema(description = "최초로 읽음 처리한 STAFF ID", example = "3")
        Long readByStaffId
) {
}
