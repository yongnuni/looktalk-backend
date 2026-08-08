package com.looktalk.backend.e2ee.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 38. E2EE-002
public record E2eeKeyStatusResponse(
        @Schema(description = "공개키 등록 여부", example = "true")
        boolean registered,

        @Schema(description = "현재 활성 키 버전 (미등록이면 null)", example = "1", nullable = true)
        Integer keyVersion,

        @Schema(description = "현재 활성 공개키 (미등록이면 null)", example = "BASE64_PUBLIC_KEY", nullable = true)
        String publicKey
) {
}
