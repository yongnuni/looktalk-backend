package com.looktalk.backend.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// docs/LookTalk_Backend_API_Spec_v2.1.md 52~55. CHAT-001~004
@Entity
@Table(name = "chat_room")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private ChatRoomType roomType;

    // HOSPITAL:{작은 userId}:{큰 userId} 또는 SMS:{currentUserId}:{normalizedFriendPhone}.
    // ux_chat_room_conversation_key 부분 UNIQUE 인덱스(V6)가 1:1 room 중복 생성을 막는 최종 방어선이다.
    @Column(name = "conversation_key", length = 200)
    private String conversationKey;

    @Column(name = "created_at")
    private Instant createdAt;
}
