package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// docs/LookTalk_Backend_API_Spec_v2.1.md 54, 55. CHAT-003/004 find-or-create 공통 최소 Response.
// 상대방 정보/마지막 메시지는 포함하지 않는다 - 필요하면 CHAT-002/CHAT-005를 사용한다.
public record ChatRoomCreateResponse(
        @Schema(description = "채팅방 ID", example = "10")
        Long roomId,

        @Schema(description = "채팅방 종류", example = "HOSPITAL")
        String roomType,

        @Schema(description = "채팅방이 처음 생성된 시각 (기존 room을 반환한 경우 이번 조회 시각이 아니다)")
        Instant createdAt
) {
}
