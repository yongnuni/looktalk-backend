package com.looktalk.backend.e2ee.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 37. E2EE-001 Success
public record E2eeKeyRegisterResponse(
        @Schema(description = "등록된 키 버전", example = "1")
        Integer keyVersion
) {
}
