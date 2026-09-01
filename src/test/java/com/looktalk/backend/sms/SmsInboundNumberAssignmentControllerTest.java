package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// §8 - STAFF 전용 SMS 회신 번호 배정 endpoint. PATIENT는 호출할 수 없고, 동일 번호가 이미 다른 환자에게
// active로 배정되어 있으면 거부되어야 한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmsInboundNumberAssignmentControllerTest {

    private static final String STAFF_ID = "sinact-staff";
    private static final String PATIENT_A = "sinact-patient-a";
    private static final String PATIENT_B = "sinact-patient-b";
    private static final String SERVICE_NUMBER = "0212341111";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SmsInboundNumberAssignmentRepository assignmentRepository;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(patient(STAFF_ID, UserRole.STAFF));
        userRepository.save(patient(PATIENT_A, UserRole.PATIENT));
        userRepository.save(patient(PATIENT_B, UserRole.PATIENT));
    }

    @AfterEach
    void cleanUp() {
        for (String userId : new String[] {STAFF_ID, PATIENT_A, PATIENT_B}) {
            userRepository.findById(userId).ifPresent(u -> {
                assignmentRepository.findByUser_UserIdAndActiveTrue(userId)
                        .ifPresent(a -> assignmentRepository.deleteById(a.getAssignmentId()));
                userRepository.deleteById(userId);
            });
        }
    }

    @Test
    void staffCanAssignNumberToPatient() throws Exception {
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ID, UserRole.STAFF);

        HttpResponse<String> response = assign(staffToken, PATIENT_A, SERVICE_NUMBER);
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertTrue(json.get("success").asBoolean());
        assertEquals(SERVICE_NUMBER, json.get("data").get("serviceNumber").asText());
        assertTrue(assignmentRepository.findByUser_UserIdAndActiveTrue(PATIENT_A).isPresent());
    }

    @Test
    void patientCannotCallStaffAssignmentEndpoint() throws Exception {
        String patientToken = jwtTokenProvider.generateAccessToken(PATIENT_A, UserRole.PATIENT);

        HttpResponse<String> response = assign(patientToken, PATIENT_A, SERVICE_NUMBER);

        assertEquals(403, response.statusCode());
    }

    @Test
    void assigningAlreadyActiveNumberToAnotherPatientIsRejected() throws Exception {
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_ID, UserRole.STAFF);
        assertEquals(200, assign(staffToken, PATIENT_A, SERVICE_NUMBER).statusCode());

        HttpResponse<String> conflict = assign(staffToken, PATIENT_B, SERVICE_NUMBER);
        JsonNode json = MAPPER.readTree(conflict.body());

        assertEquals(409, conflict.statusCode());
        assertEquals("SMS_INBOUND_NUMBER_ALREADY_ASSIGNED", json.get("code").asText());
    }

    private HttpResponse<String> assign(String token, String patientUserId, String serviceNumber) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/staff/patients/" + patientUserId + "/sms-inbound-number"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString("{\"serviceNumber\":\"" + serviceNumber + "\"}", StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private User patient(String userId, UserRole role) {
        return User.builder()
                .userId(userId).userEmail(userId + "@sinact.local").userPwd("unused-in-this-test")
                .isEmailVerified(true).isSmsVerified(false)
                .createdAt(Instant.now()).loginId(userId + "-login").role(role).build();
    }
}
