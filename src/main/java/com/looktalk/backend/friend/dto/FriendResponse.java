package com.looktalk.backend.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 48. FRIEND-001
// 외부 SMS 지인이므로 내부 회원의 displayName 로직을 쓰지 않고 friendship.friend_name/friend_phone을 그대로 반환한다.
public record FriendResponse(
        @Schema(description = "friendship ID", example = "7")
        Long friendshipId,

        @Schema(description = "지인 이름", example = "엄마")
        String name,

        @Schema(description = "지인 전화번호(정규화됨)", example = "01012345678")
        String phone
) {
}
