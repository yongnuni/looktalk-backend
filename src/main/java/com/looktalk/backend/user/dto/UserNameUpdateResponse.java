package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 27. USER-002 Response
public record UserNameUpdateResponse(
        @Schema(description = "내부 사용자 ID", example = "U001")
        String userId,

        @Schema(description = "설정된 이름 (없으면 null)", example = "김민준", nullable = true)
        String name,

        @Schema(description = "화면 표시용 이름", example = "김민준")
        String displayName
) {
}
