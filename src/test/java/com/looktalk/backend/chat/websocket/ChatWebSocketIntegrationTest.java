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
import com.looktalk.backend.hospital.entity.Hospital;
import com.looktalk.backend.hospital.repository.HospitalRepository;
import com.looktalk.backend.staff.entity.Staff;
import com.looktalk.backend.staff.entity.StaffPatientAssignment;
import com.looktalk.backend.staff.repository.StaffPatientAssignmentRepository;
import com.looktalk.backend.staff.repository.StaffRepository;
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
import java.util.List;
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
    private static final String STAFF_WITH_ALIAS = "wsit-staff-aliased";
    private static final String STAFF_WITHOUT_ALIAS = "wsit-staff-plain";
    private static final String PATIENT_SENDER = "wsit-patient-sender";
    private static final String ALIAS_FOR_SENDER = "B의별칭_환자";

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

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private StaffPatientAssignmentRepository assignmentRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Long hospitalRoomId;
    private Long hospitalId;

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

        chatRoomRepository.findByConversationKey("WSIT-ALIAS:" + PATIENT_SENDER)
                .ifPresent(room -> chatRoomRepository.deleteById(room.getRoomId()));
        for (String userId : List.of(STAFF_WITH_ALIAS, STAFF_WITHOUT_ALIAS, PATIENT_SENDER)) {
            userRepository.findById(userId).ifPresent(u -> userRepository.deleteById(userId));
        }
        if (hospitalId != null) {
            hospitalRepository.findById(hospitalId).ifPresent(h -> hospitalRepository.deleteById(hospitalId));
            hospitalId = null;
        }
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

    // ===== 4. Step 8B - 같은 message라도 MESSAGE_CREATED payload의 senderDisplayName은 recipient마다
    // 달라야 한다(alias를 가진 STAFF, alias 없는 STAFF, sender 자기 자신). 다른 필드(messageId 등)는 동일해야 한다 =====

    @Test
    void messageCreatedEventShowsDifferentSenderDisplayNamePerRecipient() throws Exception {
        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .hospitalName("WSIT Alias Hospital")
                .inviteCode("WSIT-ALIAS-INVITE-" + System.nanoTime())
                .createdAt(Instant.now())
                .build());
        hospitalId = hospital.getHospitalId();

        User patientSender = userRepository.save(User.builder()
                .userId(PATIENT_SENDER)
                .userName("김환자")
                .userEmail(PATIENT_SENDER + "@wsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(PATIENT_SENDER + "-login")
                .role(UserRole.PATIENT)
                .build());
        User staffWithAlias = userRepository.save(User.builder()
                .userId(STAFF_WITH_ALIAS)
                .userEmail(STAFF_WITH_ALIAS + "@wsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(STAFF_WITH_ALIAS + "-login")
                .role(UserRole.STAFF)
                .build());
        User staffWithoutAlias = userRepository.save(User.builder()
                .userId(STAFF_WITHOUT_ALIAS)
                .userEmail(STAFF_WITHOUT_ALIAS + "@wsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(STAFF_WITHOUT_ALIAS + "-login")
                .role(UserRole.STAFF)
                .build());

        Staff staffAliased = staffRepository.save(Staff.builder()
                .user(staffWithAlias).hospital(hospital).createdAt(Instant.now()).build());
        staffRepository.save(Staff.builder()
                .user(staffWithoutAlias).hospital(hospital).createdAt(Instant.now()).build());
        // STAFF_WITH_ALIAS만 PATIENT_SENDER의 담당 STAFF고 alias를 갖는다. STAFF_WITHOUT_ALIAS는 담당 관계가 없다.
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffAliased).patientUser(patientSender).patientAlias(ALIAS_FOR_SENDER)
                .assignedAt(Instant.now()).build());

        // 3명(PATIENT_SENDER, STAFF_WITH_ALIAS, STAFF_WITHOUT_ALIAS)이 함께 있는 HOSPITAL room을 직접 구성한다.
        ChatRoom room = chatRoomRepository.save(ChatRoom.builder()
                .roomType(ChatRoomType.HOSPITAL)
                .conversationKey("WSIT-ALIAS:" + PATIENT_SENDER)
                .createdAt(Instant.now())
                .build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(patientSender).joinedAt(Instant.now()).withdrawn(false).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(staffWithAlias).joinedAt(Instant.now()).withdrawn(false).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(staffWithoutAlias).joinedAt(Instant.now()).withdrawn(false).build());

        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(patientSender).keyVersion(1).publicKey("wsit-sender-public-key").createdAt(Instant.now()).build());
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(staffWithAlias).keyVersion(1).publicKey("wsit-staff-alias-public-key").createdAt(Instant.now()).build());
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(staffWithoutAlias).keyVersion(1).publicKey("wsit-staff-plain-public-key").createdAt(Instant.now()).build());

        String tokenSender = jwtTokenProvider.generateAccessToken(PATIENT_SENDER, UserRole.PATIENT);
        String tokenStaffAliased = jwtTokenProvider.generateAccessToken(STAFF_WITH_ALIAS, UserRole.STAFF);
        String tokenStaffPlain = jwtTokenProvider.generateAccessToken(STAFF_WITHOUT_ALIAS, UserRole.STAFF);

        BlockingQueue<JsonNode> queueSender = subscribeAndCollect(tokenSender);
        BlockingQueue<JsonNode> queueStaffAliased = subscribeAndCollect(tokenStaffAliased);
        BlockingQueue<JsonNode> queueStaffPlain = subscribeAndCollect(tokenStaffPlain);

        String cipherForSender = Base64.getEncoder().encodeToString("cipher-for-sender".getBytes(StandardCharsets.UTF_8));
        String cipherForAliased = Base64.getEncoder().encodeToString("cipher-for-staff-aliased".getBytes(StandardCharsets.UTF_8));
        String cipherForPlain = Base64.getEncoder().encodeToString("cipher-for-staff-plain".getBytes(StandardCharsets.UTF_8));

        String body = "{"
                + "\"messageType\":\"TEXT\","
                + "\"ciphertexts\":["
                + "{\"recipientUserId\":\"" + PATIENT_SENDER + "\",\"keyVersion\":1,\"algorithm\":\"LIBSODIUM_SEALED_BOX\",\"ciphertext\":\"" + cipherForSender + "\"},"
                + "{\"recipientUserId\":\"" + STAFF_WITH_ALIAS + "\",\"keyVersion\":1,\"algorithm\":\"LIBSODIUM_SEALED_BOX\",\"ciphertext\":\"" + cipherForAliased + "\"},"
                + "{\"recipientUserId\":\"" + STAFF_WITHOUT_ALIAS + "\",\"keyVersion\":1,\"algorithm\":\"LIBSODIUM_SEALED_BOX\",\"ciphertext\":\"" + cipherForPlain + "\"}"
                + "]}";

        JsonNode response = post("/api/chat-rooms/" + room.getRoomId() + "/messages", tokenSender, body);
        assertTrue(response.get("success").asBoolean(), "CHAT-006 HOSPITAL 전송은 성공해야 한다: " + response);
        long messageId = response.get("data").get("messageId").asLong();

        JsonNode eventSender = queueSender.poll(5, TimeUnit.SECONDS);
        JsonNode eventStaffAliased = queueStaffAliased.poll(5, TimeUnit.SECONDS);
        JsonNode eventStaffPlain = queueStaffPlain.poll(5, TimeUnit.SECONDS);

        assertTrue(eventSender != null, "sender 본인도 자신의 이벤트를 받아야 한다.");
        assertTrue(eventStaffAliased != null, "alias를 가진 STAFF recipient는 자신의 이벤트를 받아야 한다.");
        assertTrue(eventStaffPlain != null, "alias가 없는 STAFF recipient도 자신의 이벤트를 받아야 한다.");

        // 불변 필드(messageId/roomId)는 recipient와 무관하게 동일해야 한다.
        assertEquals(messageId, eventSender.get("data").get("messageId").asLong());
        assertEquals(messageId, eventStaffAliased.get("data").get("messageId").asLong());
        assertEquals(messageId, eventStaffPlain.get("data").get("messageId").asLong());
        assertEquals(room.getRoomId(), eventSender.get("roomId").asLong());
        assertEquals(room.getRoomId(), eventStaffAliased.get("roomId").asLong());
        assertEquals(room.getRoomId(), eventStaffPlain.get("roomId").asLong());

        // senderDisplayName은 recipient마다 다르다 - 같은 event 객체를 재사용하면 안 된다.
        assertEquals("김환자", eventSender.get("data").get("senderDisplayName").asText(),
                "sender 본인 시점(자기 자신 view)에는 항상 전역 규칙만 보여야 한다.");
        assertEquals(ALIAS_FOR_SENDER, eventStaffAliased.get("data").get("senderDisplayName").asText(),
                "담당 STAFF recipient는 자신이 설정한 patientAlias를 봐야 한다.");
        assertEquals("김환자", eventStaffPlain.get("data").get("senderDisplayName").asText(),
                "담당 관계가 없는 STAFF recipient는 전역 규칙(userName)만 봐야 한다.");

        // ciphertext는 여전히 recipient별로 격리되어야 한다(alias 적용이 E2EE에 영향을 주지 않는다).
        assertEquals(cipherForSender, eventSender.get("data").get("encryptedPayload").get("ciphertext").asText());
        assertEquals(cipherForAliased, eventStaffAliased.get("data").get("encryptedPayload").get("ciphertext").asText());
        assertEquals(cipherForPlain, eventStaffPlain.get("data").get("encryptedPayload").get("ciphertext").asText());
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
