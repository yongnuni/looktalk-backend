package com.looktalk.backend.emergency.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket 실시간 Event.
// 현재 정의된 type은 EMERGENCY_CALL_CREATED 하나뿐이다.
public record EmergencyWebSocketEvent(
        @Schema(description = "Event 종류", example = "EMERGENCY_CALL_CREATED")
        String type,

        EmergencyCallCreatedEventData data
) {
    private static final String TYPE_EMERGENCY_CALL_CREATED = "EMERGENCY_CALL_CREATED";

    public static EmergencyWebSocketEvent emergencyCallCreated(EmergencyCallCreatedEventData data) {
        return new EmergencyWebSocketEvent(TYPE_EMERGENCY_CALL_CREATED, data);
    }
}
