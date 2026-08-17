package com.looktalk.backend.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 14. STAFF-005
// alias는 null 또는 blank(whitespace only)면 별칭 제거(NULL 저장)를 의미한다. Bean Validation에서는
// null까지 막지 않도록 @NotBlank를 쓰지 않고, 최대 길이만 검증한다(trim 전 원문 기준).
public record StaffPatientAliasUpdateRequest(
        @Schema(description = "담당 환자 별칭. null/공백이면 별칭을 삭제한다.", example = "101호_김철수", nullable = true)
        @Size(max = 50) String alias
) {
}
