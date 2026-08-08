package com.looktalk.backend.chat.repository;

import com.looktalk.backend.chat.entity.MessageCiphertext;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageCiphertextRepository extends JpaRepository<MessageCiphertext, Long> {

    // CHAT-002 - 여러 message의 "현재 사용자용" ciphertext를 한 번의 쿼리로 조회한다(N+1 방지).
    // recipient_user_id로만 매칭하므로 다른 사용자의 ciphertext는 절대 섞이지 않는다.
    List<MessageCiphertext> findAllByMessage_MessageIdInAndRecipientUser_UserId(
            Collection<Long> messageIds, String recipientUserId);
}
