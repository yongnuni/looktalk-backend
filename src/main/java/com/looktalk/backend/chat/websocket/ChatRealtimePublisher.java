package com.looktalk.backend.chat.websocket;

import com.looktalk.backend.chat.dto.ChatWebSocketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket 실시간 메시지
// ChatMessageService는 이 Publisher만 알고, broker/destination 세부사항은 여기서만 다룬다.
// WebSocket은 best-effort 실시간 전달 채널이다 - publish 실패가 이미 commit된 REST 메시지를 무효화하지
// 않도록 여기서 예외를 흡수하고 격리한다. 민감정보(ciphertext/token/plaintext)는 로그에 남기지 않고
// messageId/roomId/recipient 정도의 metadata만 warning으로 남긴다.
@Component
public class ChatRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatRealtimePublisher.class);
    private static final String USER_DESTINATION = "/queue/chat";

    private final SimpMessagingTemplate messagingTemplate;

    public ChatRealtimePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // recipientUserId는 반드시 JWT/DB의 canonical userId다(loginId/email/displayName 금지).
    public void publishMessageCreated(String recipientUserId, ChatWebSocketEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(recipientUserId, USER_DESTINATION, event);
        } catch (RuntimeException exception) {
            log.warn("WebSocket publish 실패: roomId={}, messageId={}, recipientUserId={}",
                    event.roomId(), event.data().messageId(), recipientUserId, exception);
        }
    }
}
