package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6. EMERGENCY-002 - GET ?page=&size=&unreadOnly=
public record EmergencyCallPageResponse(
        @Schema(description = "현재 페이지의 긴급 호출 목록")
        List<EmergencyCallListItemResponse> items,

        @Schema(description = "현재 페이지 번호(0-base)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 건수", example = "3")
        long totalElements,

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        boolean hasNext
) {
}
