package com.looktalk.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 21. AUTH-005 Success
public record StaffSignupResponse(
        @Schema(description = "서버에서 생성한 내부 사용자 ID", example = "U100")
        String userId,

        @Schema(description = "의료진 ID", example = "3")
        Long staffId,

        @Schema(description = "로그인 아이디", example = "doctor01")
        String loginId,

        @Schema(description = "사용자 역할", example = "STAFF")
        String role,

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
