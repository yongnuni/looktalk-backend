package com.looktalk.backend.chat.repository;

import com.looktalk.backend.chat.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // CHAT-003/004 find-or-create - conversation_key UNIQUE 인덱스(V6)와 함께 1:1 room 중복 생성을 막는다.
    Optional<ChatRoom> findByConversationKey(String conversationKey);
}
