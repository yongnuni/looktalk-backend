package com.looktalk.backend.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 34. STAFF-002
public record StaffPatientResponse(
        @Schema(description = "환자 사용자 ID", example = "P001")
        String userId,

        @Schema(description = "환자 로그인 아이디", example = "patient01")
        String loginId,

        @Schema(description = "환자 이름 (없으면 null)", example = "null", nullable = true)
        String name,

        @Schema(description = "화면 표시용 이름", example = "patient01")
        String displayName,

        @Schema(description = "담당 지정 시각")
        Instant assignedAt
) {
}
