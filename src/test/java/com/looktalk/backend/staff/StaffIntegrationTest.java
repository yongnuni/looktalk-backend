package com.looktalk.backend.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 14. STAFF-005 + GET /api/staff/me/patients
// 실제 서버(랜덤 포트)와 실제 DB로 patient_alias 저장/권한/isolation 계약을 고정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffIntegrationTest {

    private static final String STAFF_A = "sit-staff-a";
    private static final String STAFF_B = "sit-staff-b";
    private static final String PATIENT_WITH_NAME = "sit-patient-named";
    private static final String PATIENT_NO_NAME = "sit-patient-noname";
    private static final String PATIENT_SHARED = "sit-patient-shared";
    private static final String PATIENT_UNASSIGNED = "sit-patient-unassigned";
    private static final List<String> ALL_TEST_USER_IDS = List.of(
            STAFF_A, STAFF_B, PATIENT_WITH_NAME, PATIENT_NO_NAME, PATIENT_SHARED, PATIENT_UNASSIGNED);

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
                .hospitalName("SIT Hospital")
                .inviteCode("SIT-INVITE-" + System.nanoTime())
                .createdAt(Instant.now())
                .build());
        hospitalId = hospital.getHospitalId();

        User staffUserA = userRepository.save(staffUser(STAFF_A));
        User staffUserB = userRepository.save(staffUser(STAFF_B));
        User patientWithName = userRepository.save(patient(PATIENT_WITH_NAME, "김철수"));
        User patientNoName = userRepository.save(patient(PATIENT_NO_NAME, null));
        User patientShared = userRepository.save(patient(PATIENT_SHARED, "박영희"));
        userRepository.save(patient(PATIENT_UNASSIGNED, "이민수"));

        Staff staffA = staffRepository.save(Staff.builder()
                .user(staffUserA).hospital(hospital).createdAt(Instant.now()).build());
        Staff staffB = staffRepository.save(Staff.builder()
                .user(staffUserB).hospital(hospital).createdAt(Instant.now()).build());

        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffA).patientUser(patientWithName).assignedAt(Instant.now()).build());
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffA).patientUser(patientNoName).assignedAt(Instant.now()).build());
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffA).patientUser(patientShared).assignedAt(Instant.now()).build());
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffB).patientUser(patientShared).assignedAt(Instant.now()).build());
        // PATIENT_UNASSIGNED는 누구에게도 assignment가 없다.
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private User patient(String userId, String userName) {
        return User.builder()
                .userId(userId)
                .userName(userName)
                .userEmail(userId + "@sit.local")
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
                .userEmail(userId + "@sit.local")
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

    // ===== PATCH alias =====

    @Test
    void staffCanSetAliasForOwnPatient() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token,
                "{\"alias\":\"101호_김철수\"}");

        assertEquals(200, result.status());
        JsonNode data = result.body().get("data");
        assertEquals("101호_김철수", data.get("patientAlias").asText());
        assertEquals("101호_김철수", data.get("displayName").asText());
    }

    @Test
    void aliasIsTrimmed() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token,
                "{\"alias\":\"  101호_김철수  \"}");

        assertEquals(200, result.status());
        assertEquals("101호_김철수", result.body().get("data").get("patientAlias").asText());
    }

    @Test
    void nullAliasDeletesAliasAndFallsBackToUserName() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":\"101호_김철수\"}");

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":null}");

        assertEquals(200, result.status());
        assertTrue(result.body().get("data").get("patientAlias").isNull());
        assertEquals("김철수", result.body().get("data").get("displayName").asText());
    }

    @Test
    void blankAliasDeletesAlias() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":\"101호_김철수\"}");

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":\"   \"}");

        assertEquals(200, result.status());
        assertTrue(result.body().get("data").get("patientAlias").isNull());
        assertEquals("김철수", result.body().get("data").get("displayName").asText());
    }

    @Test
    void alias50CharsIsAllowed() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        String alias50 = "a".repeat(50);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token,
                "{\"alias\":\"" + alias50 + "\"}");

        assertEquals(200, result.status());
        assertEquals(alias50, result.body().get("data").get("patientAlias").asText());
    }

    @Test
    void alias51CharsIsRejected() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        String alias51 = "a".repeat(51);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token,
                "{\"alias\":\"" + alias51 + "\"}");

        assertEquals(400, result.status());
    }

    @Test
    void unassignedPatientReturns404() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_UNASSIGNED + "/alias", token,
                "{\"alias\":\"별칭\"}");

        assertEquals(404, result.status());
        assertEquals("STAFF_PATIENT_NOT_ASSIGNED", result.body().get("code").asText());
    }

    @Test
    void cannotModifyOtherStaffsAssignment() throws Exception {
        String tokenA = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        String tokenB = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        // PATIENT_WITH_NAME은 STAFF_A에게만 assignment가 있다.
        HttpResult resultB = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", tokenB,
                "{\"alias\":\"침입 시도\"}");
        assertEquals(404, resultB.status());
        assertEquals("STAFF_PATIENT_NOT_ASSIGNED", resultB.body().get("code").asText());

        // STAFF_A의 assignment는 영향받지 않아야 한다(alias 미설정 상태 유지).
        HttpResult listA = get("/api/staff/me/patients", tokenA);
        JsonNode patientRow = findPatientRow(listA.body(), PATIENT_WITH_NAME);
        assertTrue(patientRow.get("patientAlias").isNull());
    }

    @Test
    void patientForbiddenToPatchAlias() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_WITH_NAME, UserRole.PATIENT);

        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token,
                "{\"alias\":\"별칭\"}");

        assertEquals(403, result.status());
    }

    @Test
    void unauthenticatedForbiddenToPatchAlias() throws Exception {
        HttpResult result = patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", null,
                "{\"alias\":\"별칭\"}");

        assertEquals(401, result.status());
    }

    // ===== GET /api/staff/me/patients =====

    @Test
    void listShowsAliasAsDisplayNameWhenPresent() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":\"101호_김철수\"}");

        HttpResult result = get("/api/staff/me/patients", token);

        JsonNode row = findPatientRow(result.body(), PATIENT_WITH_NAME);
        assertEquals("101호_김철수", row.get("patientAlias").asText());
        assertEquals("101호_김철수", row.get("displayName").asText());
        assertEquals("김철수", row.get("name").asText());
    }

    @Test
    void listFallsBackToUserNameWhenNoAlias() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/staff/me/patients", token);

        JsonNode row = findPatientRow(result.body(), PATIENT_WITH_NAME);
        assertTrue(row.get("patientAlias").isNull());
        assertEquals("김철수", row.get("displayName").asText());
    }

    @Test
    void listFallsBackToLoginIdWhenNoAliasAndNoUserName() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/staff/me/patients", token);

        JsonNode row = findPatientRow(result.body(), PATIENT_NO_NAME);
        assertTrue(row.get("patientAlias").isNull());
        assertTrue(row.get("name").isNull());
        assertEquals(PATIENT_NO_NAME + "-login", row.get("displayName").asText());
    }

    @Test
    void existingListFieldsArePreserved() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/staff/me/patients", token);

        JsonNode row = findPatientRow(result.body(), PATIENT_WITH_NAME);
        assertEquals(PATIENT_WITH_NAME, row.get("userId").asText());
        assertEquals(PATIENT_WITH_NAME + "-login", row.get("loginId").asText());
        assertTrue(row.has("assignedAt") && !row.get("assignedAt").isNull());
    }

    // ===== isolation =====

    @Test
    void sameSharedPatientHasIndependentAliasPerStaff() throws Exception {
        String tokenA = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        String tokenB = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        patch("/api/staff/me/patients/" + PATIENT_SHARED + "/alias", tokenA, "{\"alias\":\"A병동_박영희\"}");
        patch("/api/staff/me/patients/" + PATIENT_SHARED + "/alias", tokenB, "{\"alias\":\"B병동_박영희\"}");

        JsonNode rowA = findPatientRow(get("/api/staff/me/patients", tokenA).body(), PATIENT_SHARED);
        JsonNode rowB = findPatientRow(get("/api/staff/me/patients", tokenB).body(), PATIENT_SHARED);

        assertEquals("A병동_박영희", rowA.get("patientAlias").asText());
        assertEquals("B병동_박영희", rowB.get("patientAlias").asText());
    }

    @Test
    void deletingOneStaffsAliasDoesNotAffectTheOther() throws Exception {
        String tokenA = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);
        String tokenB = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        patch("/api/staff/me/patients/" + PATIENT_SHARED + "/alias", tokenA, "{\"alias\":\"A병동_박영희\"}");
        patch("/api/staff/me/patients/" + PATIENT_SHARED + "/alias", tokenB, "{\"alias\":\"B병동_박영희\"}");

        patch("/api/staff/me/patients/" + PATIENT_SHARED + "/alias", tokenA, "{\"alias\":null}");

        JsonNode rowA = findPatientRow(get("/api/staff/me/patients", tokenA).body(), PATIENT_SHARED);
        JsonNode rowB = findPatientRow(get("/api/staff/me/patients", tokenB).body(), PATIENT_SHARED);

        assertTrue(rowA.get("patientAlias").isNull());
        assertEquals("박영희", rowA.get("displayName").asText());
        assertEquals("B병동_박영희", rowB.get("patientAlias").asText());
    }

    // ===== regression =====

    @Test
    void usersUserNameIsNeverOverwrittenByAlias() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        patch("/api/staff/me/patients/" + PATIENT_WITH_NAME + "/alias", token, "{\"alias\":\"101호_김철수\"}");

        User reloaded = userRepository.findById(PATIENT_WITH_NAME).orElseThrow();
        assertEquals("김철수", reloaded.getUserName());
    }

    // ===== helpers =====

    private JsonNode findPatientRow(JsonNode responseBody, String patientUserId) {
        for (JsonNode row : responseBody.get("data")) {
            if (patientUserId.equals(row.get("userId").asText())) {
                return row;
            }
        }
        throw new AssertionError("patient row not found for " + patientUserId + ": " + responseBody);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResult patch(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", BodyPublishers.ofString(body, StandardCharsets.UTF_8));
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

    private record HttpResult(int status, JsonNode body) {
    }
}
