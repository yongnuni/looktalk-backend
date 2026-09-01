package com.looktalk.backend.sms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record SmsInboundNumberAssignmentRequest(
        @Schema(description = "환자에게 배정할 U+ MO 수신용 서비스 번호", example = "07012345678")
        @NotBlank String serviceNumber
) {
}
