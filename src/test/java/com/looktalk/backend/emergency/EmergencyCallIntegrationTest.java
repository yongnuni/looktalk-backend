package com.looktalk.backend.emergency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.emergency.repository.EmergencyCallRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 20. 필수 테스트 - Emergency
// 실제 서버(랜덤 포트)와 실제 DB로 EMERGENCY-001~003의 권한/ownership/동시성 계약을 고정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmergencyCallIntegrationTest {

    private static final String PATIENT_A = "eit-patient-a";
    private static final String PATIENT_SOLO = "eit-patient-solo";
    private static final String STAFF_ASSIGNED = "eit-staff-assigned";
    private static final String STAFF_ASSIGNED_2 = "eit-staff-assigned-2";
    private static final String STAFF_UNASSIGNED = "eit-staff-unassigned";
    private static final List<String> ALL_TEST_USER_IDS =
            List.of(PATIENT_A, PATIENT_SOLO, STAFF_ASSIGNED, STAFF_ASSIGNED_2, STAFF_UNASSIGNED);

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

    @Autowired
    private EmergencyCallRepository emergencyCallRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Long hospitalId;
    private Long staffAssignedId;
    private Long staffAssigned2Id;

    @BeforeEach
    void setUp() {
        cleanUpTestData();

        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .hospitalName("EIT Hospital")
                .inviteCode("EIT-INVITE-" + System.nanoTime())
                .createdAt(Instant.now())
                .build());
        hospitalId = hospital.getHospitalId();

        User patientA = userRepository.save(patient(PATIENT_A));
        userRepository.save(patient(PATIENT_SOLO));
        User staffUserAssigned = userRepository.save(staffUser(STAFF_ASSIGNED));
        User staffUserAssigned2 = userRepository.save(staffUser(STAFF_ASSIGNED_2));
        User staffUserUnassigned = userRepository.save(staffUser(STAFF_UNASSIGNED));

        Staff staffAssigned = staffRepository.save(Staff.builder()
                .user(staffUserAssigned).hospital(hospital).createdAt(Instant.now()).build());
        Staff staffAssigned2 = staffRepository.save(Staff.builder()
                .user(staffUserAssigned2).hospital(hospital).createdAt(Instant.now()).build());
        staffRepository.save(Staff.builder()
                .user(staffUserUnassigned).hospital(hospital).createdAt(Instant.now()).build());
        staffAssignedId = staffAssigned.getStaffId();
        staffAssigned2Id = staffAssigned2.getStaffId();

        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffAssigned).patientUser(patientA).assignedAt(Instant.now()).build());
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffAssigned2).patientUser(patientA).assignedAt(Instant.now()).build());
        // staffUnassigned는 patientA에 대한 assignment가 없다.
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private User patient(String userId) {
        return User.builder()
                .userId(userId)
                .userEmail(userId + "@eit.local")
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
                .userEmail(userId + "@eit.local")
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

    // ===== EMERGENCY-001 =====

    @Test
    void patientCreatesCallSuccessfully() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult result = post("/api/emergency-calls", token, "");

        assertEquals(201, result.status());
        assertTrue(result.body().get("success").asBoolean());
        assertTrue(result.body().get("data").get("emergencyCallId").isNumber());
        assertTrue(result.body().get("data").get("calledAt").isTextual());
    }

    @Test
    void staffCannotCreateCall() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);

        HttpResult result = post("/api/emergency-calls", token, "");

        assertEquals(403, result.status());
    }

    @Test
    void unauthenticatedCannotCreateCall() throws Exception {
        HttpResult result = post("/api/emergency-calls", null, "");

        assertEquals(401, result.status());
    }

    @Test
    void patientWithNoAssignedStaffCanStillCreateCall() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_SOLO, UserRole.PATIENT);

        HttpResult result = post("/api/emergency-calls", token, "");

        assertEquals(201, result.status());
        assertTrue(result.body().get("success").asBoolean());
    }

    // ===== EMERGENCY-002 =====

    @Test
    void assignedStaffSeesCallButUnassignedDoesNot() throws Exception {
        long callId = createCall(PATIENT_A);

        HttpResult assignedView = get("/api/emergency-calls?page=0&size=20",
                jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF));
        HttpResult unassignedView = get("/api/emergency-calls?page=0&size=20",
                jwtTokenProvider.generateAccessToken(STAFF_UNASSIGNED, UserRole.STAFF));

        assertEquals(200, assignedView.status());
        JsonNode assignedItems = assignedView.body().get("data").get("items");
        assertEquals(1, assignedItems.size());
        assertEquals(callId, assignedItems.get(0).get("emergencyCallId").asLong());
        assertEquals(PATIENT_A, assignedItems.get(0).get("patientUserId").asText());
        assertEquals(PATIENT_A + "-login", assignedItems.get(0).get("patientDisplayName").asText());
        assertTrue(assignedItems.get(0).get("unread").asBoolean());

        assertEquals(200, unassignedView.status());
        assertEquals(0, unassignedView.body().get("data").get("items").size());
    }

    @Test
    void unreadOnlyFilterHidesReadCalls() throws Exception {
        long callId1 = createCall(PATIENT_A);
        long callId2 = createCall(PATIENT_A);
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        patch("/api/emergency-calls/" + callId1 + "/read", staffToken);

        HttpResult unreadOnly = get("/api/emergency-calls?page=0&size=20&unreadOnly=true", staffToken);
        HttpResult all = get("/api/emergency-calls?page=0&size=20&unreadOnly=false", staffToken);

        JsonNode unreadItems = unreadOnly.body().get("data").get("items");
        assertEquals(1, unreadItems.size());
        assertEquals(callId2, unreadItems.get(0).get("emergencyCallId").asLong());

        assertEquals(2, all.body().get("data").get("items").size());
    }

    // ===== EMERGENCY-003 =====

    @Test
    void firstReadOnlyPersists() throws Exception {
        long callId = createCall(PATIENT_A);
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        String staff2Token = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED_2, UserRole.STAFF);

        HttpResult firstRead = patch("/api/emergency-calls/" + callId + "/read", staffToken);
        assertEquals(200, firstRead.status());
        assertEquals(staffAssignedId, firstRead.body().get("data").get("readByStaffId").asLong());
        String firstReadAt = firstRead.body().get("data").get("readAt").asText();

        HttpResult secondRead = patch("/api/emergency-calls/" + callId + "/read", staff2Token);
        assertEquals(200, secondRead.status());
        assertEquals(staffAssignedId, secondRead.body().get("data").get("readByStaffId").asLong(),
                "두 번째로 읽음 처리를 시도한 STAFF가 최초 reader를 덮어쓰면 안 된다.");
        assertEquals(firstReadAt, secondRead.body().get("data").get("readAt").asText());
    }

    @Test
    void unassignedStaffCannotReadAndHidesExistence() throws Exception {
        long callId = createCall(PATIENT_A);

        HttpResult result = patch("/api/emergency-calls/" + callId + "/read",
                jwtTokenProvider.generateAccessToken(STAFF_UNASSIGNED, UserRole.STAFF));

        assertEquals(404, result.status());
        assertEquals("EMERGENCY_CALL_NOT_FOUND", result.body().get("code").asText());
    }

    @Test
    void nonexistentCallReadReturns404() throws Exception {
        HttpResult result = patch("/api/emergency-calls/999999999/read",
                jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF));

        assertEquals(404, result.status());
        assertEquals("EMERGENCY_CALL_NOT_FOUND", result.body().get("code").asText());
    }

    @Test
    void concurrentReadsPreserveSingleFirstReader() throws Exception {
        long callId = createCall(PATIENT_A);
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        String staff2Token = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED_2, UserRole.STAFF);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<HttpResult> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return patch("/api/emergency-calls/" + callId + "/read", staffToken);
            });
            java.util.concurrent.Future<HttpResult> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return patch("/api/emergency-calls/" + callId + "/read", staff2Token);
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            HttpResult r1 = f1.get(10, TimeUnit.SECONDS);
            HttpResult r2 = f2.get(10, TimeUnit.SECONDS);

            assertEquals(200, r1.status());
            assertEquals(200, r2.status());

            long winner1 = r1.body().get("data").get("readByStaffId").asLong();
            long winner2 = r2.body().get("data").get("readByStaffId").asLong();
            assertEquals(winner1, winner2, "동시 읽음 요청이라도 최종 readByStaffId는 하나로 일치해야 한다.");
            assertTrue(winner1 == staffAssignedId || winner1 == staffAssigned2Id);
            assertEquals(r1.body().get("data").get("readAt").asText(), r2.body().get("data").get("readAt").asText());
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== USER-005 상호작용 =====

    @Test
    void readingStaffWithdrawalPreservesCallRowAndSetsReadByStaffNull() throws Exception {
        long callId = createCall(PATIENT_A);
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ASSIGNED, UserRole.STAFF);
        patch("/api/emergency-calls/" + callId + "/read", staffToken);

        // USER-005의 핵심 메커니즘(users DELETE -> staff ON DELETE CASCADE)만 직접 검증한다.
        userRepository.deleteById(STAFF_ASSIGNED);

        var reloaded = emergencyCallRepository.findById(callId).orElseThrow();
        assertTrue(reloaded.getReadAt() != null, "읽은 STAFF가 탈퇴해도 read_at은 보존되어야 한다.");
        assertNull(reloaded.getReadByStaff(), "read_by_staff_id는 ON DELETE SET NULL로 정리되어야 한다.");
    }

    @Test
    void patientWithdrawalCascadesDeleteEmergencyCalls() throws Exception {
        long callId = createCall(PATIENT_A);
        assertTrue(emergencyCallRepository.findById(callId).isPresent());

        userRepository.deleteById(PATIENT_A);

        assertFalse(emergencyCallRepository.findById(callId).isPresent(),
                "환자 탈퇴 시 emergency_call은 ON DELETE CASCADE로 함께 삭제되어야 한다.");
    }

    // ===== helpers =====

    private long createCall(String patientUserId) throws Exception {
        String token = jwtTokenProvider.generateAccessToken(patientUserId, UserRole.PATIENT);
        HttpResult result = post("/api/emergency-calls", token, "");
        return result.body().get("data").get("emergencyCallId").asLong();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResult post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), MAPPER.readTree(response.body()));
    }

    private HttpResult get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), MAPPER.readTree(response.body()));
    }

    private HttpResult patch(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", BodyPublishers.ofString("", StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), MAPPER.readTree(response.body()));
    }

    private record HttpResult(int status, JsonNode body) {
    }
}
