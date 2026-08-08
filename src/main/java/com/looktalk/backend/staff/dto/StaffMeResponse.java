package com.looktalk.backend.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 33. STAFF-001
public record StaffMeResponse(
        @Schema(description = "의료진 ID", example = "3")
        Long staffId,

        @Schema(description = "내부 사용자 ID", example = "U100")
        String userId,

        @Schema(description = "로그인 아이디", example = "doctor01")
        String loginId,

        @Schema(description = "이메일", example = "doctor@hospital.com")
        String email,

        @Schema(description = "마이페이지에서 설정한 이름 (없으면 null)", example = "null", nullable = true)
        String name,

        @Schema(description = "화면 표시용 이름", example = "doctor01")
        String displayName,

        HospitalSummary hospital
) {
    public record HospitalSummary(
            @Schema(description = "병원 ID", example = "1")
            Long hospitalId,

            @Schema(description = "병원명", example = "서울OO병원")
            String hospitalName
    ) {
    }
}
