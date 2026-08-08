package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 32. SET-002
// PATCH 요청에 포함된(null이 아닌) 필드만 변경한다.
public record UserSettingUpdateRequest(
        @Schema(description = "키보드 레이아웃. 미포함 시 변경하지 않는다.", example = "QWERTY", nullable = true)
        @Size(max = 20) String keyboardLayout,

        @Schema(description = "키 확대 여부. 미포함 시 변경하지 않는다.", example = "true", nullable = true)
        Boolean keyEnlarged,

        @Schema(description = "현재 입력 방식. 미포함 시 변경하지 않는다.", example = "BLINK",
                allowableValues = {"EYE_TRACKING", "BLINK", "MOUTH"}, nullable = true)
        @Pattern(regexp = "EYE_TRACKING|BLINK|MOUTH") String currentInputMethod
) {
}
