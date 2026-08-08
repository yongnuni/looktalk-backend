package com.looktalk.backend.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 35. STAFF-003
public record StaffPatientAddRequest(
        @Schema(description = "담당 환자로 추가할 환자의 로그인 아이디", example = "patient01")
        @NotBlank @Size(max = 50) String patientLoginId
) {
}
