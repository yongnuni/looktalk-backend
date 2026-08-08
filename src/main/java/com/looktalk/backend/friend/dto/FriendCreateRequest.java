package com.looktalk.backend.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 49. FRIEND-002 - name/phone 모두 필수다.
// 길이 제한은 friendship.friend_name VARCHAR(50) / friend_phone VARCHAR(20) 컬럼 정의를 따른다.
public record FriendCreateRequest(
        @Schema(description = "지인 이름", example = "엄마")
        @NotBlank @Size(max = 50) String name,

        @Schema(description = "지인 전화번호", example = "01012345678")
        @NotBlank @Size(max = 20) String phone
) {
}
