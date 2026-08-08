package com.looktalk.backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket 실시간 메시지 Event.
// 현재 정의된 type은 MESSAGE_CREATED 하나뿐이다.
public record ChatWebSocketEvent(
        @Schema(description = "Event 종류", example = "MESSAGE_CREATED")
        String type,

        @Schema(description = "채팅방 ID", example = "10")
        Long roomId,

        ChatMessageCreatedEventData data
) {
    private static final String TYPE_MESSAGE_CREATED = "MESSAGE_CREATED";

    public static ChatWebSocketEvent messageCreated(Long roomId, ChatMessageCreatedEventData data) {
        return new ChatWebSocketEvent(TYPE_MESSAGE_CREATED, roomId, data);
    }
}
