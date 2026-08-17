package com.looktalk.backend.emergency.websocket;

import com.looktalk.backend.emergency.dto.EmergencyWebSocketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket
// ChatRealtimePublisher와 동일한 원칙: WebSocket은 best-effort 실시간 전달 채널이다 - publish 실패가 이미
// commit된 emergency_call 저장/REST 201 응답을 무효화하지 않도록 여기서 예외를 흡수하고 격리한다.
// 민감정보(이메일/전화번호/토큰 등)는 로그에 남기지 않고 emergencyCallId/recipient 정도의 metadata만 warning으로 남긴다.
@Component
public class EmergencyRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(EmergencyRealtimePublisher.class);
    private static final String USER_DESTINATION = "/queue/emergency";

    private final SimpMessagingTemplate messagingTemplate;

    public EmergencyRealtimePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // recipientUserId는 반드시 JWT/DB의 canonical userId다(loginId/email/displayName 금지).
    public void publishEmergencyCallCreated(String recipientUserId, EmergencyWebSocketEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(recipientUserId, USER_DESTINATION, event);
        } catch (RuntimeException exception) {
            log.warn("WebSocket publish 실패: emergencyCallId={}, recipientUserId={}",
                    event.data().emergencyCallId(), recipientUserId, exception);
        }
    }
}
