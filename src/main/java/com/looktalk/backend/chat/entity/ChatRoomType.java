package com.looktalk.backend.chat.entity;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53. CHAT-002 - chat_room.room_type에는 아직 DB CHECK 제약이 없지만
// 이번 단계에서 Backend가 실제로 생성하는 값은 HOSPITAL/SMS 두 가지뿐이다.
public enum ChatRoomType {
    HOSPITAL,
    SMS
}
