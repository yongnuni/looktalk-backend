package com.looktalk.backend.global.exception;

import java.util.List;

// docs/LookTalk_Backend_API_Spec_v2.1.md 14. Validation 규칙 - COMMON_VALIDATION_ERROR data 구조를 그대로 따른다.
public record ValidationErrorData(
        List<FieldErrorDetail> fieldErrors
) {
    public record FieldErrorDetail(
            String field,
            String reason
    ) {
    }
}
