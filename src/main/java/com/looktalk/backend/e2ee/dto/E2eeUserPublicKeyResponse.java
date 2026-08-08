package com.looktalk.backend.e2ee.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 39. E2EE-003
// 대상 사용자의 공개키 조회 전용 응답. 다른 사용자의 개인정보(이메일, 전화번호 등)는 포함하지 않는다.
public record E2eeUserPublicKeyResponse(
        @Schema(description = "대상 사용자 ID", example = "U100")
        String userId,

        @Schema(description = "대상 사용자의 현재 활성 키 버전", example = "1")
        Integer keyVersion,

        @Schema(description = "대상 사용자의 현재 활성 공개키", example = "BASE64_PUBLIC_KEY")
        String publicKey
) {
}
