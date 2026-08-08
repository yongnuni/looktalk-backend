package com.looktalk.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 31. SET-001
public record UserSettingResponse(
        @Schema(description = "키보드 레이아웃", example = "QWERTY")
        String keyboardLayout,

        @Schema(description = "키 확대 여부", example = "false")
        boolean keyEnlarged,

        @Schema(description = "현재 입력 방식", example = "EYE_TRACKING", allowableValues = {"EYE_TRACKING", "BLINK", "MOUTH"})
        String currentInputMethod
) {
}
