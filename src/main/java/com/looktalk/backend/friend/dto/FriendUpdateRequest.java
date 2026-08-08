package com.looktalk.backend.friend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 50. FRIEND-003 - name/phone은 선택이며 전달된 필드만 수정한다.
// 최소 한 필드는 전달되어야 하고, 전달된 필드는 빈 값을 허용하지 않는다. 이 조건은 Bean Validation으로
// 표현할 수 없어(둘 다 nullable이면서 "둘 다 null이면 거절") FriendService에서 직접 검증한다.
public record FriendUpdateRequest(
        @Schema(description = "지인 이름 (미전달 시 기존 값 유지)", example = "어머니", nullable = true)
        @Size(max = 50) String name,

        @Schema(description = "지인 전화번호 (미전달 시 기존 값 유지)", example = "01099998888", nullable = true)
        @Size(max = 20) String phone
) {
}
