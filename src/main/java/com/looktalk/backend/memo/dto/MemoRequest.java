package com.looktalk.backend.memo.dto;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// docs/LookTalk_Backend_API_Spec_v2.1.md 41. MEMO-002 / 42. MEMO-003
// 등록과 수정 Request 형식이 동일해 하나의 DTO로 공유한다.
public record MemoRequest(
        @NotNull @Valid EncryptedPayload encryptedPayload
) {
}
