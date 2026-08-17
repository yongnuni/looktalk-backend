package com.looktalk.backend.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.user.entity.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 2. Destination allowlist를 /user/queue/chat,
// /user/queue/emergency 두 개로 확장한 뒤에도 프레임 단위 인증/구독/SEND 차단 정책이 정확히 유지되는지
// 결정적으로(비-네트워크) 검증한다. CONNECT -> 이후 프레임 간 Principal 연속성은 실제 WebSocket 세션에서만
// 재현되므로 그 부분은 ChatWebSocketIntegrationTest/EmergencyWebSocketIntegrationTest가 담당한다.
class WebSocketAuthChannelInterceptorTest {

    private static final String SECRET = "unit-test-jwt-secret-must-be-at-least-32-bytes!!";

    private JwtTokenProvider jwtTokenProvider;
    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, 900);
        interceptor = new WebSocketAuthChannelInterceptor(jwtTokenProvider);
    }

    @Test
    void connectWithValidBearerHeaderSucceeds() {
        // accessor.setUser()가 실제 WebSocket 세션에 반영되어 이후 프레임까지 유지되는지는 Spring의 STOMP 세션
        // 인프라(SimpAttributes) 영역이라 이 단위 테스트로는 재현하지 않는다 - 그 계약은
        // ChatWebSocketIntegrationTest/EmergencyWebSocketIntegrationTest가 실제 세션으로 검증한다. 여기서는
        // "유효한 JWT면 CONNECT 자체가 예외 없이 통과한다"는 순수 로직만 결정적으로 확인한다.
        String token = jwtTokenProvider.generateAccessToken("u1", UserRole.PATIENT);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        // 실제 STOMP 디코더와 동일하게 leaveMutable(true)를 설정해야 preSend 내부의 accessor.setUser() 호출이
        // "이미 immutable" 예외 없이 통과한다.
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertDoesNotThrow(() -> interceptor.preSend(message, null));
    }

    @Test
    void connectWithoutAuthorizationHeaderThrows() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void connectWithInvalidJwtThrows() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer this-is-not-a-valid-jwt");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void subscribeToChatQueueWithAuthenticatedUserSucceeds() {
        Message<byte[]> message = authenticatedSubscribeMessage("/user/queue/chat", "u1");

        assertDoesNotThrow(() -> interceptor.preSend(message, null));
    }

    @Test
    void subscribeToEmergencyQueueWithAuthenticatedUserSucceeds() {
        Message<byte[]> message = authenticatedSubscribeMessage("/user/queue/emergency", "u1");

        assertDoesNotThrow(() -> interceptor.preSend(message, null));
    }

    @Test
    void subscribeToDisallowedDestinationThrows() {
        Message<byte[]> message = authenticatedSubscribeMessage("/user/queue/other", "u1");

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void subscribeWithoutAuthenticatedUserThrows() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/chat");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void sendCommandAlwaysThrowsRegardlessOfAuthentication() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/anything");
        accessor.setUser(authenticationOf("u1"));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(MessagingException.class, () -> interceptor.preSend(message, null));
    }

    private Message<byte[]> authenticatedSubscribeMessage(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(authenticationOf(userId));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Authentication authenticationOf(String userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }
}
