package com.looktalk.backend.e2ee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// docs/LookTalk_Backend_API_Spec_v2.1.md 37. E2EE-001
// keyVersion은 클라이언트가 지정하지 않는다. 서버가 현재 사용자의 다음 버전(1부터 순차 증가)을 계산해 저장하고
// Response로 반환한다.
public record E2eeKeyRegisterRequest(
        @Schema(description = "Base64로 인코딩된 libsodium sealed box 공개키. 디코딩 시 정확히 32바이트여야 한다.",
                example = "BASE64_PUBLIC_KEY")
        @NotBlank @Size(max = 4000) String publicKey
) {
}
