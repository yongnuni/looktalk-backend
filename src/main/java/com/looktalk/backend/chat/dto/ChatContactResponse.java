package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 52. CHAT-001
public record ChatContactResponse(
        @Schema(description = "내부 회원 ID", example = "U100")
        String userId,

        @Schema(description = "역할", example = "STAFF")
        String role,

        @Schema(description = "설정된 이름 (없으면 null)", example = "김철수", nullable = true)
        String name,

        @Schema(description = "표시 이름. name이 없으면 loginId를 사용한다.", example = "김철수")
        String displayName,

        @Schema(description = "현재 활성 E2EE 공개키 등록 여부", example = "true")
        boolean e2eeReady
) {
}
