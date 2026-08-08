package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53. CHAT-002 - HOSPITAL room(type=USER)과 SMS room(type=SMS)이 같은
// 구조를 공유하되 해당 없는 필드는 null이다. USER는 userId/displayName을 쓰고 phone은 없다.
// SMS는 name/phone만 쓰고 userId/displayName은 없다(내부 회원이 아니므로 DisplayNameResolver를 쓰지 않는다).
public record ChatRoomTargetResponse(
        @Schema(description = "상대방 종류", example = "USER")
        String type,

        @Schema(description = "내부 회원 ID (SMS면 null)", example = "U100", nullable = true)
        String userId,

        @Schema(description = "이름", example = "김철수")
        String name,

        @Schema(description = "표시 이름 (SMS면 null)", example = "김철수", nullable = true)
        String displayName,

        @Schema(description = "외부 지인 전화번호 (USER면 null)", example = "01012345678", nullable = true)
        String phone
) {
}
