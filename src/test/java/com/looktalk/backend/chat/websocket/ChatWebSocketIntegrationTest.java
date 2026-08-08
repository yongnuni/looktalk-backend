package com.looktalk.backend.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.ChatRoom;
import com.looktalk.backend.chat.entity.ChatRoomType;
import com.looktalk.backend.chat.repository.ChatParticipantRepository;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket 실시간 메시지
// 실제 서버(랜덤 포트)와 실제 STOMP client로 인증 경계와 recipient별 ciphertext 격리 계약을 고정한다.
// 과거에 WebSocketAuthChannelInterceptor가 StompHeaderAccessor.wrap(message)로 만든 accessor에 setUser()를
// 호출해도 CONNECT에서 설정한 Principal이 이후 SUBSCRIBE 프레임까지 유지되지 않는 회귀가 실제로 있었다
// (MessageHeaderAccessor.getAccessor(message, ...)로 수정). 이 테스트는 그 회귀가 재발하면 즉시 실패해야 한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketIntegrationTest {

    private static final String USER_A = "wsit-a";
    private static final String USER_B = "wsit-b";
    private static final String USER_C_NOT_IN_ROOM = "wsit-c-outsider";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatParticipantRepository chatParticipantRepository;

    @Autowired
    private UserE2eeKeyRepository userE2eeKeyRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Long hospitalRoomId;

    @BeforeEach
    void setUp() {
        cleanUpTestData();

        User userA = userRepository.save(User.builder()
                .userId(USER_A)
                .userEmail(USER_A + "@wsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(USER_A + "-login")
                .role(UserRole.PATIENT)
                .build());
        User userB = userRepository.save(User.builder()
                .userId(USER_B)
                .userEmail(USER_B + "@wsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(USER_B + "-login")
                .role(UserRole.STAFF)
                .build());

        ChatRoom room = chatRoomRepository.save(ChatRoom.builder()
                .roomType(ChatRoomType.HOSPITAL)
                .conversationKey("HOSPITAL:" + USER_A + ":" + USER_B)
                .createdAt(Instant.now())
                .build());
        hospitalRoomId = room.getRoomId();

        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(userA).joinedAt(Instant.now()).withdrawn(false).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(userB).joinedAt(Instant.now()).withdrawn(false).build());

        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(userA).keyVersion(1).publicKey("wsit-a-public-key").createdAt(Instant.now()).build());
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(userB).keyVersion(1).publicKey("wsit-b-public-key").createdAt(Instant.now()).build());
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private void cleanUpTestData() {
        chatRoomRepository.findByConversationKey("HOSPITAL:" + USER_A + ":" + USER_B)
                .ifPresent(room -> chatRoomRepository.deleteById(room.getRoomId()));
        userRepository.findById(USER_A).ifPresent(u -> userRepository.deleteById(USER_A));
        userRepository.findById(USER_B).ifPresent(u -> userRepository.deleteById(USER_B));
    }

    // ===== 1. 유효한 JWT: CONNECT -> /user/queue/chat SUBSCRIBE 후에도 세션이 유지되어야 한다 =====

    @Test
    void validJwtConnectsAndStaysSubscribedToOwnQueue() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(USER_A, UserRole.PATIENT);

        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        StompSession session = connect("Bearer " + token, failure);

        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 이 테스트에서는 프레임 수신 여부가 아니라 구독 이후 세션 유지 여부만 확인한다.
            }
        });

        Thread.sleep(500);
        assertTrue(session.isConnected(),
                "CONNECT에서 인증된 Principal이 SUBSCRIBE까지 유지되지 않으면(과거 회귀) 세션이 끊긴다. failure=" + failure.get());
        session.disconnect();
    }

    // ===== 2. JWT 없음/유효하지 않음: CONNECT 자체가 실패해야 한다 =====

    @Test
    void connectWithoutAuthorizationHeaderFails() {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> connect(null, failure));
        assertTrue(isConnectFailure(ex), "예상치 못한 예외 타입: " + ex);
    }

    @Test
    void connectWithInvalidJwtFails() {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> connect("Bearer this-is-not-a-valid-jwt", failure));
        assertTrue(isConnectFailure(ex), "예상치 못한 예외 타입: " + ex);
    }

    // ===== 3. HOSPITAL A/B room에서 A가 전송 -> recipient별 ciphertext 격리, 비참여자는 수신 없음 =====

    @Test
    void hospitalSendDeliversIsolatedCiphertextOnlyToRoomParticipants() throws Exception {
        String tokenA = jwtTokenProvider.generateAccessToken(USER_A, UserRole.PATIENT);
        String tokenB = jwtTokenProvider.generateAccessToken(USER_B, UserRole.STAFF);
        String tokenC = jwtTokenProvider.generateAccessToken(USER_C_NOT_IN_ROOM, UserRole.PATIENT);

        BlockingQueue<JsonNode> queueA = subscribeAndCollect(tokenA);
        BlockingQueue<JsonNode> queueB = subscribeAndCollect(tokenB);
        BlockingQueue<JsonNode> queueC = subscribeAndCollect(tokenC);

        String cipherForA = Base64.getEncoder().encodeToString("cipher-for-wsit-a".getBytes(StandardCharsets.UTF_8));
        String cipherForB = Base64.getEncoder().encodeToString("cipher-for-wsit-b".getBytes(StandardCharsets.UTF_8));

        String body = "{"
                + "\"messageType\":\"TEXT\","
                + "\"ciphertexts\":["
                + "{\"recipientUserId\":\"" + USER_A + "\",\"keyVersion\":1,\"algorithm\":\"LIBSODIUM_SEALED_BOX\",\"ciphertext\":\"" + cipherForA + "\"},"
                + "{\"recipientUserId\":\"" + USER_B + "\",\"keyVersion\":1,\"algorithm\":\"LIBSODIUM_SEALED_BOX\",\"ciphertext\":\"" + cipherForB + "\"}"
                + "]}";

        JsonNode response = post("/api/chat-rooms/" + hospitalRoomId + "/messages", tokenA, body);
        assertTrue(response.get("success").asBoolean(), "CHAT-006 HOSPITAL 전송은 성공해야 한다: " + response);
        long messageId = response.get("data").get("messageId").asLong();

        JsonNode eventA = queueA.poll(5, TimeUnit.SECONDS);
        JsonNode eventB = queueB.poll(5, TimeUnit.SECONDS);
        JsonNode eventC = queueC.poll(1500, TimeUnit.MILLISECONDS);

        assertTrue(eventA != null, "A(sender)는 자신의 이벤트를 받아야 한다.");
        assertTrue(eventB != null, "B(recipient)는 자신의 이벤트를 받아야 한다.");
        assertNull(eventC, "room에 속하지 않은 사용자는 이벤트를 받으면 안 된다.");

        assertEquals(messageId, eventA.get("data").get("messageId").asLong());
        assertEquals(cipherForA, eventA.get("data").get("encryptedPayload").get("ciphertext").asText(),
                "A는 자신의 ciphertext만 받아야 한다.");
        assertTrue(!cipherForB.equals(eventA.get("data").get("encryptedPayload").get("ciphertext").asText()),
                "A가 B의 ciphertext를 받으면 안 된다.");

        assertEquals(messageId, eventB.get("data").get("messageId").asLong());
        assertEquals(cipherForB, eventB.get("data").get("encryptedPayload").get("ciphertext").asText(),
                "B는 자신의 ciphertext만 받아야 한다.");
        assertTrue(!cipherForA.equals(eventB.get("data").get("encryptedPayload").get("ciphertext").asText()),
                "B가 A의 ciphertext를 받으면 안 된다.");
    }

    // ===== helpers =====

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private StompSession connect(String authorizationHeaderValue, java.util.concurrent.atomic.AtomicReference<Throwable> failure)
            throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (authorizationHeaderValue != null) {
            connectHeaders.add("Authorization", authorizationHeaderValue);
        }
        CompletableFuture<StompSession> future = client.connectAsync(
                wsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        failure.set(exception);
                    }

                    @Override
                    public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                            byte[] payload, Throwable exception) {
                        failure.set(exception);
                    }
                });
        return future.get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribeAndCollect(String token) throws Exception {
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        StompSession session = connect("Bearer " + token, new java.util.concurrent.atomic.AtomicReference<>());
        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((JsonNode) payload);
            }
        });
        Thread.sleep(300);
        return queue;
    }

    private boolean isConnectFailure(Throwable ex) {
        Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
        return cause instanceof org.springframework.messaging.simp.stomp.ConnectionLostException
                || cause instanceof java.util.concurrent.TimeoutException
                || cause instanceof java.util.concurrent.CancellationException
                || cause instanceof java.io.IOException
                || ex instanceof java.util.concurrent.TimeoutException;
    }

    private JsonNode post(String path, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }
}
