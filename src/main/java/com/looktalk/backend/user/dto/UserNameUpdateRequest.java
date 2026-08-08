package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 27. USER-002
// name=null이면 이름을 삭제한다.
public record UserNameUpdateRequest(
        @Schema(description = "설정할 이름. null이면 이름을 삭제한다.", example = "김민준", nullable = true)
        @Size(max = 50) String name
) {
}
