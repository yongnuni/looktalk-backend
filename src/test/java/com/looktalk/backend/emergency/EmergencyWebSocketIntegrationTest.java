package com.looktalk.backend.emergency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket
// 실제 서버(랜덤 포트)와 실제 STOMP client로 /user/queue/emergency 인증/구독 경계와 담당 STAFF에게만 실시간
// EMERGENCY_CALL_CREATED가 전달되는 계약을 고정한다. ChatWebSocketIntegrationTest와 동일한 스타일을 재사용한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmergencyWebSocketIntegrationTest {

    private static final String PATIENT_A = "ewsit-patient-a";
    private static final String PATIENT_SOLO = "ewsit-patient-solo";
    private static final String STAFF_ASSIGNED = "ewsit-staff-assigned";
    private static final String STAFF_UNASSIGNED = "ewsit-staff-unassigned";
    private static final List<String> ALL_TEST_USER_IDS =
            List.of(PATIENT_A, PATIENT_SOLO, STAFF_ASSIGNED, STAFF_UNASSIGNED);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private StaffPatientAssignmentRepository assignmentRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Long hospitalId;

    @BeforeEach
    void setUp() {
        cleanUpTestData();

        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .hospitalName("EWSIT Hospital")
                .inviteCode("EWSIT-INVITE-" + System.nanoTime())
                .createdAt(Instant.now())
                .build());
        hospitalId = hospital.getHospitalId();

        User patientA = userRepository.save(patient(PATIENT_A));
        userRepository.save(patient(PATIENT_SOLO));
        User staffUserAssigned = userRepository.save(staffUser(STAFF_ASSIGNED));
        User staffUserUnassigned = userRepository.save(staffUser(STAFF_UNASSIGNED));

        Staff staffAssigned = staffRepository.save(Staff.builder()
                .user(staffUserAssigned).hospital(hospital).createdAt(Instant.now()).build());
        staffRepository.save(Staff.builder()
                .user(staffUserUnassigned).hospital(hospital).createdAt(Instant.now()).build());

        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffAssigned).patientUser(patientA).assignedAt(Instant.now()).build());
        // STAFF_UNASSIGNED는 patientA에 대한 assignment가 없다.
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private User patient(String userId) {
        return User.builder()
                .userId(userId)
                .userEmail(userId + "@ewsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(userId + "-login")
                .role(UserRole.PATIENT)
                .build();
    }

    private User staffUser(String userId) {
        return User.builder()
                .userId(userId)
                .userEmail(userId + "@ewsit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(userId + "-login")
                .role(UserRole.STAFF)
                .build();
    }

    private void cleanUpTestData() {
        for (String userId : ALL_TEST_USER_IDS) {
            userRepository.findById(userId).ifPresent(u -> userRepository.deleteById(userId));
        }
        if (hospitalId != null) {
            hospitalRepository.findById(hospitalId).ifPresent(h -> hospitalRepository.deleteById(hospitalId));
            hospitalId = null;
        }
    }

    // ===== 1. 인증/구독 =====

    @Test
    void validJwtConnectsAndStaysSubscribedToEmergencyQueue() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        StompSession session = connect("Bearer " + token, failure);

        session.subscribe("/user/queue/emergency", new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 이 테스트는 구독 이후 세션 유지 여부만 확인한다.
            }
        });

        Thread.sleep(500);
        assertTrue(session.isConnected(), "failure=" + failure.get());
        session.disconnect();
    }

    @Test
    void connectWithoutAuthorizationHeaderFails() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> connect(null, failure));
        assertTrue(isConnectFailure(ex), "예상치 못한 예외 타입: " + ex);
    }

    @Test
    void connectWithInvalidJwtFails() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> connect("Bearer this-is-not-a-valid-jwt", failure));
        assertTrue(isConnectFailure(ex), "예상치 못한 예외 타입: " + ex);
    }

    @Test
    void chatQueueSubscriptionStillSucceedsAfterAllowlistExpansion() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StompSession session = connect("Bearer " + token, failure);

        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 이 테스트는 기존 /user/queue/chat 구독이 계속 성공하는지만 확인한다.
            }
        });

        Thread.sleep(500);
        assertTrue(session.isConnected(), "/user/queue/emergency 추가 이후에도 기존 /user/queue/chat 구독은 깨지면 안 된다. failure="
                + failure.get());
        session.disconnect();
    }

    // ===== 2. Emergency delivery =====

    @Test
    void emergencyCallDeliversOnlyToAssignedStaff() throws Exception {
        String patientToken = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);
        String assignedToken = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        String unassignedToken = jwtTokenProvider.generateAccessToken(STAFF_UNASSIGNED, UserRole.STAFF);

        BlockingQueue<JsonNode> assignedQueue = subscribeAndCollect(assignedToken);
        BlockingQueue<JsonNode> unassignedQueue = subscribeAndCollect(unassignedToken);

        JsonNode response = post("/api/emergency-calls", patientToken);
        assertTrue(response.get("success").asBoolean(), "EMERGENCY-001은 성공해야 한다: " + response);
        long emergencyCallId = response.get("data").get("emergencyCallId").asLong();

        JsonNode assignedEvent = assignedQueue.poll(5, TimeUnit.SECONDS);
        JsonNode unassignedEvent = unassignedQueue.poll(1500, TimeUnit.MILLISECONDS);

        assertTrue(assignedEvent != null, "담당 STAFF는 EMERGENCY_CALL_CREATED 이벤트를 받아야 한다.");
        assertNull(unassignedEvent, "미담당 STAFF는 이벤트를 받으면 안 된다.");

        assertEquals("EMERGENCY_CALL_CREATED", assignedEvent.get("type").asText());
        JsonNode data = assignedEvent.get("data");
        assertEquals(emergencyCallId, data.get("emergencyCallId").asLong());
        assertEquals(PATIENT_A, data.get("patientUserId").asText());
        assertEquals(PATIENT_A + "-login", data.get("patientDisplayName").asText());
        assertTrue(data.get("calledAt") != null && !data.get("calledAt").isNull());
    }

    @Test
    void patientWithNoAssignedStaffStillSucceedsWithNoRecipients() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_SOLO, UserRole.PATIENT);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/emergency-calls"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> httpResponse = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode response = MAPPER.readTree(httpResponse.body());

        assertEquals(201, httpResponse.statusCode());
        assertTrue(response.get("success").asBoolean());
        assertTrue(response.get("data").get("emergencyCallId").isNumber());
    }

    // ===== helpers =====

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private StompSession connect(String authorizationHeaderValue, AtomicReference<Throwable> failure) throws Exception {
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
        StompSession session = connect("Bearer " + token, new AtomicReference<>());
        session.subscribe("/user/queue/emergency", new StompFrameHandler() {
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

    private JsonNode post(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }
}
