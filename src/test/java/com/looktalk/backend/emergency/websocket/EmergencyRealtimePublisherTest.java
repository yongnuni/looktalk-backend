package com.looktalk.backend.emergency.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.looktalk.backend.emergency.dto.EmergencyCallCreatedEventData;
import com.looktalk.backend.emergency.dto.EmergencyWebSocketEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket - "WebSocket publish 실패는
// 이미 commit된 REST/DB 상태를 무효화하지 않는다"는 정책의 핵심 보장 지점이 EmergencyRealtimePublisher다.
// ChatRealtimePublisherTest와 동일한 failure isolation pattern을 재사용한다.
@ExtendWith(MockitoExtension.class)
class EmergencyRealtimePublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void publishFailureIsSwallowedAndDoesNotPropagate() {
        EmergencyRealtimePublisher publisher = new EmergencyRealtimePublisher(messagingTemplate);
        EmergencyWebSocketEvent event = sampleEvent();

        doThrow(new MessagingException("broker down"))
                .when(messagingTemplate)
                .convertAndSendToUser(eq("staff-1"), eq("/queue/emergency"), any());

        assertDoesNotThrow(() -> publisher.publishEmergencyCallCreated("staff-1", event),
                "publish 실패가 호출자(EmergencyCallService)로 전파되면 이미 commit된 REST 201 응답에 영향을 줄 수 있다.");
    }

    @Test
    void publishSuccessRoutesToRecipientUserQueue() {
        EmergencyRealtimePublisher publisher = new EmergencyRealtimePublisher(messagingTemplate);
        EmergencyWebSocketEvent event = sampleEvent();

        publisher.publishEmergencyCallCreated("staff-1", event);

        verify(messagingTemplate).convertAndSendToUser("staff-1", "/queue/emergency", event);
    }

    private EmergencyWebSocketEvent sampleEvent() {
        EmergencyCallCreatedEventData data =
                new EmergencyCallCreatedEventData(10L, "patient-1", "김철수", Instant.now());
        return EmergencyWebSocketEvent.emergencyCallCreated(data);
    }
}
