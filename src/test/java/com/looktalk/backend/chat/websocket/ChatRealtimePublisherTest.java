package com.looktalk.backend.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.looktalk.backend.chat.dto.ChatMessageCreatedEventData;
import com.looktalk.backend.chat.dto.ChatWebSocketEvent;
import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket - "WebSocket publish 실패는 이미 commit된
// REST/DB 상태를 무효화하지 않는다"는 정책의 핵심 보장 지점이 ChatRealtimePublisher다.
// ChatMessageService는 publishMessageCreated 호출 결과를 감시하지 않으므로, 이 메서드가 어떤 RuntimeException도
// 밖으로 던지지 않는다는 것 자체가 그 정책의 실제 구현이다.
@ExtendWith(MockitoExtension.class)
class ChatRealtimePublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void publishFailureIsSwallowedAndDoesNotPropagate() {
        ChatRealtimePublisher publisher = new ChatRealtimePublisher(messagingTemplate);
        ChatWebSocketEvent event = sampleEvent();

        doThrow(new MessagingException("broker down"))
                .when(messagingTemplate)
                .convertAndSendToUser(eq("b1"), eq("/queue/chat"), any());

        assertDoesNotThrow(() -> publisher.publishMessageCreated("b1", event),
                "publish 실패가 호출자(ChatMessageService)로 전파되면 이미 commit된 REST 응답에 영향을 줄 수 있다.");
    }

    @Test
    void publishSuccessRoutesToRecipientUserQueue() {
        ChatRealtimePublisher publisher = new ChatRealtimePublisher(messagingTemplate);
        ChatWebSocketEvent event = sampleEvent();

        publisher.publishMessageCreated("b1", event);

        verify(messagingTemplate).convertAndSendToUser("b1", "/queue/chat", event);
    }

    private ChatWebSocketEvent sampleEvent() {
        ChatMessageCreatedEventData data = new ChatMessageCreatedEventData(
                1L, 1L, "a1", "usera",
                new EncryptedPayload("LIBSODIUM_SEALED_BOX", 1, "Y2lwaGVy"),
                Instant.now());
        return ChatWebSocketEvent.messageCreated(1L, data);
    }
}
