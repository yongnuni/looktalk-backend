package com.looktalk.backend.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.calibration.entity.Calibration;
import com.looktalk.backend.calibration.repository.CalibrationRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 11. CAL-001/CAL-002
// 실제 서버(랜덤 포트)와 실제 DB로 Calibration REST의 권한/validation/lifecycle/동시성/JSONB round trip을 고정한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CalibrationIntegrationTest {

    private static final String PATIENT_A = "calit-patient-a";
    private static final String STAFF_A = "calit-staff-a";
    private static final List<String> ALL_TEST_USER_IDS = List.of(PATIENT_A, STAFF_A);

    private static final String VALID_CALIBRATION_DATA = "{"
            + "\"schemaVersion\":1,"
            + "\"mappingType\":\"RAW_HOMOGRAPHY\","
            + "\"coordinateSpace\":\"NORMALIZED_VIEWPORT\","
            + "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]],"
            + "\"mirrorX\":true,"
            + "\"mirrorStrategy\":\"PRE_INFERENCE_FRAME_FLIP\","
            + "\"grid\":{\"rows\":4,\"cols\":4,\"margin\":0.08},"
            + "\"calibratedViewport\":{\"widthPx\":1920,\"heightPx\":1080,\"aspectRatio\":1.7777777778,\"orientation\":\"landscape\"},"
            + "\"reprojectionRmseNormalized\":0.012"
            + "}";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CalibrationRepository calibrationRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        cleanUpTestData();
        userRepository.save(patient(PATIENT_A));
        userRepository.save(staffUser(STAFF_A));
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private User patient(String userId) {
        return User.builder()
                .userId(userId)
                .userEmail(userId + "@calit.local")
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
                .userEmail(userId + "@calit.local")
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
    }

    private String requestBody(String algorithmVersion, String calibrationDataJson) {
        return "{\"algorithmVersion\":\"" + algorithmVersion + "\",\"calibrationData\":" + calibrationDataJson + "}";
    }

    // ===== CAL-001 인증/권한 =====

    @Test
    void createCalibrationSucceedsForPatientWithBackendUuidAndNoInputMethodField() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult result = post("/api/calibrations", token,
                requestBody("raw-homography-v1", VALID_CALIBRATION_DATA));

        assertEquals(201, result.status());
        assertTrue(result.body().get("success").asBoolean());
        JsonNode data = result.body().get("data");
        String calibrationId = data.get("calibrationId").asText();
        assertNotNull(calibrationId);
        UUID.fromString(calibrationId); // Backend UUID 형식이 아니면 IllegalArgumentException으로 테스트 실패
        assertEquals("raw-homography-v1", data.get("algorithmVersion").asText());
        assertNotNull(data.get("calibrationData"));
        assertFalse(data.has("inputMethod"), "Calibration Response에 inputMethod 필드가 있으면 안 된다.");
        assertFalse(result.body().toString().contains("\"inputMethod\""),
                "Calibration Response 전체에 inputMethod 문자열이 존재하면 안 된다.");
    }

    @Test
    void createCalibrationFailsForUnauthenticated() throws Exception {
        HttpResult result = post("/api/calibrations", null, requestBody("raw-homography-v1", VALID_CALIBRATION_DATA));

        assertEquals(401, result.status());
    }

    @Test
    void createCalibrationFailsForStaff() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = post("/api/calibrations", token, requestBody("raw-homography-v1", VALID_CALIBRATION_DATA));

        assertEquals(403, result.status());
    }

    // ===== payload validation =====

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloadCases")
    void createCalibrationRejectsInvalidPayload(String description, String invalidCalibrationDataJson) throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult result = post("/api/calibrations", token,
                requestBody("raw-homography-v1", invalidCalibrationDataJson));

        assertEquals(400, result.status(), description + " -> 400이어야 한다: " + result.body());
        assertEquals("CALIBRATION_INVALID_PAYLOAD", result.body().get("code").asText(), description);
    }

    static Stream<Arguments> invalidPayloadCases() {
        return Stream.of(
                Arguments.of("schemaVersion != 1",
                        VALID_CALIBRATION_DATA.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
                Arguments.of("wrong mappingType",
                        VALID_CALIBRATION_DATA.replace("\"mappingType\":\"RAW_HOMOGRAPHY\"", "\"mappingType\":\"RIDGE\"")),
                Arguments.of("wrong coordinateSpace",
                        VALID_CALIBRATION_DATA.replace(
                                "\"coordinateSpace\":\"NORMALIZED_VIEWPORT\"", "\"coordinateSpace\":\"SCREEN_PX\"")),
                Arguments.of("homography 2x3",
                        VALID_CALIBRATION_DATA.replace(
                                "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
                                "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0]]")),
                Arguments.of("homography row with 4 values",
                        VALID_CALIBRATION_DATA.replace(
                                "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
                                "\"homography\":[[1.0,0.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]")),
                Arguments.of("homography non-finite (huge exponent overflows to Infinity)",
                        VALID_CALIBRATION_DATA.replace(
                                "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
                                "\"homography\":[[1E400,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]")),
                Arguments.of("mirrorX = false",
                        VALID_CALIBRATION_DATA.replace("\"mirrorX\":true", "\"mirrorX\":false")),
                Arguments.of("invalid mirrorStrategy",
                        VALID_CALIBRATION_DATA.replace(
                                "\"mirrorStrategy\":\"PRE_INFERENCE_FRAME_FLIP\"",
                                "\"mirrorStrategy\":\"UNKNOWN_STRATEGY\"")),
                Arguments.of("grid.rows != 4",
                        VALID_CALIBRATION_DATA.replace(
                                "\"grid\":{\"rows\":4,\"cols\":4,\"margin\":0.08}",
                                "\"grid\":{\"rows\":3,\"cols\":4,\"margin\":0.08}")),
                Arguments.of("grid.margin != 0.08",
                        VALID_CALIBRATION_DATA.replace("\"margin\":0.08", "\"margin\":0.5")),
                Arguments.of("calibratedViewport.widthPx <= 0",
                        VALID_CALIBRATION_DATA.replace("\"widthPx\":1920", "\"widthPx\":0")),
                Arguments.of("calibratedViewport.aspectRatio <= 0",
                        VALID_CALIBRATION_DATA.replace("\"aspectRatio\":1.7777777778", "\"aspectRatio\":0")),
                Arguments.of("invalid orientation",
                        VALID_CALIBRATION_DATA.replace("\"orientation\":\"landscape\"", "\"orientation\":\"diagonal\"")),
                Arguments.of("negative reprojectionRmseNormalized",
                        VALID_CALIBRATION_DATA.replace(
                                "\"reprojectionRmseNormalized\":0.012", "\"reprojectionRmseNormalized\":-0.1")),
                Arguments.of("unknown top-level field (raw biometric-like) not silently stored",
                        VALID_CALIBRATION_DATA.replace(
                                "\"reprojectionRmseNormalized\":0.012}",
                                "\"reprojectionRmseNormalized\":0.012,\"rawLandmarks\":[1,2,3]}"))
        );
    }

    // ===== lifecycle =====

    @Test
    void secondSaveRevokesFirstKeepsHistoryAndGetReturnsLatest() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult first = post("/api/calibrations", token, requestBody("raw-homography-v1", VALID_CALIBRATION_DATA));
        assertEquals(201, first.status());
        String firstId = first.body().get("data").get("calibrationId").asText();

        assertEquals(1, activeCount(PATIENT_A));

        String secondPayload = VALID_CALIBRATION_DATA.replace("\"widthPx\":1920", "\"widthPx\":1280");
        HttpResult second = post("/api/calibrations", token, requestBody("raw-homography-v1", secondPayload));
        assertEquals(201, second.status());
        String secondId = second.body().get("data").get("calibrationId").asText();
        assertNotEquals(firstId, secondId);

        assertEquals(1, activeCount(PATIENT_A), "revoke + insert 이후에도 active는 정확히 1개여야 한다.");

        Calibration firstRow = calibrationRepository.findById(firstId).orElseThrow();
        assertNotNull(firstRow.getRevokedAt(), "첫 번째 calibration은 revoke되어야 한다.");

        HttpResult activeResult = get("/api/calibrations/active", token);
        assertEquals(200, activeResult.status());
        assertEquals(secondId, activeResult.body().get("data").get("calibrationId").asText(),
                "GET active는 가장 최근에 저장된(두 번째) calibration을 반환해야 한다.");

        // historical revoked row는 DB에 그대로 남아 있어야 한다(delete 아님).
        assertTrue(calibrationRepository.findById(firstId).isPresent());
    }

    @Test
    void failedValidationDoesNotRevokeExistingActive() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult first = post("/api/calibrations", token, requestBody("raw-homography-v1", VALID_CALIBRATION_DATA));
        assertEquals(201, first.status());
        String firstId = first.body().get("data").get("calibrationId").asText();

        String invalidPayload = VALID_CALIBRATION_DATA.replace("\"schemaVersion\":1", "\"schemaVersion\":99");
        HttpResult failed = post("/api/calibrations", token, requestBody("raw-homography-v1", invalidPayload));
        assertEquals(400, failed.status());

        // 새 저장이 validation에서 실패했으므로 기존 active는 그대로 유지되어야 한다(rollback).
        Calibration firstRow = calibrationRepository.findById(firstId).orElseThrow();
        assertNull(firstRow.getRevokedAt(), "validation 실패 시 기존 active가 revoke되면 안 된다.");
        assertEquals(1, activeCount(PATIENT_A));

        HttpResult activeResult = get("/api/calibrations/active", token);
        assertEquals(firstId, activeResult.body().get("data").get("calibrationId").asText());
    }

    @Test
    void concurrentSavesResultInExactlyOneActiveCalibration() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        String payloadA = VALID_CALIBRATION_DATA.replace("\"widthPx\":1920", "\"widthPx\":1111");
        String payloadB = VALID_CALIBRATION_DATA.replace("\"widthPx\":1920", "\"widthPx\":2222");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResult> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return post("/api/calibrations", token, requestBody("raw-homography-v1", payloadA));
            });
            Future<HttpResult> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return post("/api/calibrations", token, requestBody("raw-homography-v1", payloadB));
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            HttpResult r1 = f1.get(10, TimeUnit.SECONDS);
            HttpResult r2 = f2.get(10, TimeUnit.SECONDS);

            assertEquals(201, r1.status());
            assertEquals(201, r2.status());

            assertEquals(1, activeCount(PATIENT_A), "동시 POST 두 건이 끝난 뒤에도 active는 정확히 1개여야 한다.");
            assertEquals(2, calibrationRepository.findAll().stream()
                    .filter(c -> c.getUser().getUserId().equals(PATIENT_A)).count());
        } finally {
            pool.shutdownNow();
        }
    }

    private long activeCount(String userId) {
        return calibrationRepository.findAll().stream()
                .filter(c -> c.getUser().getUserId().equals(userId) && c.getRevokedAt() == null)
                .count();
    }

    // ===== CAL-002 =====

    @Test
    void getActiveCalibrationReturns404WhenNoneExists() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResult result = get("/api/calibrations/active", token);

        assertEquals(404, result.status());
        assertEquals("CALIBRATION_NOT_FOUND", result.body().get("code").asText());
    }

    @Test
    void getActiveCalibrationForbiddenForStaff() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/calibrations/active", token);

        assertEquals(403, result.status());
    }

    @Test
    void getActiveCalibrationUnauthenticated() throws Exception {
        HttpResult result = get("/api/calibrations/active", null);

        assertEquals(401, result.status());
    }

    // ===== JSONB round trip =====

    @Test
    void jsonbRoundTripPreservesHomographyDoubleValuesAndMetadata() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);
        String preciseHomography = "[[1.234567,0.0,0.1],[0.0,1.0,0.0],[0.0,0.0,1.0]]";
        String payload = VALID_CALIBRATION_DATA.replace(
                "\"homography\":[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
                "\"homography\":" + preciseHomography);

        HttpResult created = post("/api/calibrations", token, requestBody("raw-homography-v1", payload));
        assertEquals(201, created.status());

        HttpResult active = get("/api/calibrations/active", token);
        assertEquals(200, active.status());
        JsonNode data = active.body().get("data").get("calibrationData");

        assertEquals(1.234567, data.get("homography").get(0).get(0).asDouble(), 1e-9);
        assertEquals(0.1, data.get("homography").get(0).get(2).asDouble(), 1e-9);
        assertEquals("PRE_INFERENCE_FRAME_FLIP", data.get("mirrorStrategy").asText());
        assertEquals(1920, data.get("calibratedViewport").get("widthPx").asInt());
        assertEquals("landscape", data.get("calibratedViewport").get("orientation").asText());
        assertEquals(0.012, data.get("reprojectionRmseNormalized").asDouble(), 1e-9);
    }

    // ===== helpers =====

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

    private record HttpResult(int status, JsonNode body) {
    }
}
