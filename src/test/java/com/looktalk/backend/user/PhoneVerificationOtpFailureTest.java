package com.looktalk.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.chat.service.SmsSendFailedException;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.service.SmsSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003 - Provider(SmsSender) 발송 실패가 SMS_SEND_FAILED(502)로
// 정확히 매핑되는지, 그리고 §6에서 method 레벨 @Transactional을 제거한 뒤에도 VerificationCode 발급 자체는
// 정상 커밋되는지(§14 최종 보고의 "무해한 잔여 row" 설명과 일치) 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhoneVerificationOtpFailureTest {

    private static final String USER_ID = "pvof-patient";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SmsSender smsSender;

    @BeforeEach
    void setUp() {
        userRepository.findById(USER_ID).ifPresent(userRepository::delete);
        userRepository.save(User.builder()
                .userId(USER_ID)
                .userEmail(USER_ID + "@pvof.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(USER_ID + "-login")
                .role(UserRole.PATIENT)
                .build());
    }

    @Test
    void providerFailureMapsToSmsSendFailed() throws Exception {
        doThrow(new SmsSendFailedException("provider down"))
                .when(smsSender).sendVerificationCode(anyString(), anyString());

        String accessToken = jwtTokenProvider.generateAccessToken(USER_ID, UserRole.PATIENT);
        HttpResponse<String> response = postPhoneVerification(accessToken, "01011112222");
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(502, response.statusCode());
        assertFalse(json.get("success").asBoolean());
        assertEquals("SMS_SEND_FAILED", json.get("code").asText());
    }

    private HttpResponse<String> postPhoneVerification(String accessToken, String phone) throws Exception {
        String body = "{\"phone\":\"" + phone + "\"}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/users/me/phone-verifications"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
