package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.service.SmsMessageSender;
import com.looktalk.backend.chat.service.SmsOutboundMessage;
import com.looktalk.backend.chat.service.SmsSendFailedException;
import com.looktalk.backend.chat.service.SmsSendResult;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.sms.entity.SmsDeliveryStatus;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsMessageDelivery;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
import com.looktalk.backend.sms.repository.SmsMessageDeliveryRepository;
import com.looktalk.backend.user.entity.Friendship;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.FriendshipRepository;
import com.looktalk.backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS + 이번 조사(§13/§14) - 환자별 회신 번호 사용,
// SmsMessageDelivery 상태 전이, client idempotency key를 실제 HTTP/DB로 검증한다. 실제 SOLAPI 네트워크는
// 호출하지 않고 SmsMessageSender를 mock으로 대체한다(§32 "실제 SOLAPI/U+ 네트워크를 CI에서 호출하지 않는다").
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatMessageServiceSmsOutboundIntegrationTest {

    private static final String PATIENT_ID = "cmsoit-patient";
    private static final String PATIENT_NO_ASSIGNMENT_ID = "cmsoit-patient-no-assign";
    private static final String FRIEND_PHONE = "01099990000";
    private static final String SERVICE_NUMBER = "0212345678";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserE2eeKeyRepository userE2eeKeyRepository;

    @Autowired
    private SmsInboundNumberAssignmentRepository assignmentRepository;

    @Autowired
    private SmsMessageDeliveryRepository smsMessageDeliveryRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @MockitoBean
    private SmsMessageSender smsMessageSender;

    private Long smsRoomId;
    private String friendshipPublicKeyPatientToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        reset(smsMessageSender);
        when(smsMessageSender.isConfigured()).thenReturn(true);

        User patient = userRepository.save(User.builder()
                .userId(PATIENT_ID)
                .userEmail(PATIENT_ID + "@cmsoit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(true)
                .userPhone("01011110000")
                .createdAt(Instant.now())
                .loginId(PATIENT_ID + "-login")
                .role(UserRole.PATIENT)
                .build());
        userRepository.save(User.builder()
                .userId(PATIENT_NO_ASSIGNMENT_ID)
                .userEmail(PATIENT_NO_ASSIGNMENT_ID + "@cmsoit.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(true)
                .userPhone("01011110001")
                .createdAt(Instant.now())
                .loginId(PATIENT_NO_ASSIGNMENT_ID + "-login")
                .role(UserRole.PATIENT)
                .build());

        // SealedBoxEncryptor는 실제 libsodium 호출이므로 진짜 32byte 공개키가 필요하다(SealedBoxEncryptorTest와
        // 동일한 방식으로 생성).
        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(patient).keyVersion(1).publicKey(Base64.getEncoder().encodeToString(publicKey))
                .createdAt(Instant.now()).build());

        Friendship friendship = friendshipRepository.save(Friendship.builder()
                .user(patient).friendUser(null).friendName("친구")
                .friendPhone(FRIEND_PHONE).status("ACCEPTED").createdAt(Instant.now()).build());

        Instant now = Instant.now();
        assignmentRepository.save(SmsInboundNumberAssignment.builder()
                .user(patient).provider(SmsProvider.UPLUS_MO).serviceNumber(SERVICE_NUMBER)
                .active(true).activatedAt(now).createdAt(now).updatedAt(now).build());

        String patientToken = jwtTokenProvider.generateAccessToken(PATIENT_ID, UserRole.PATIENT);
        friendshipPublicKeyPatientToken = patientToken;

        JsonNode roomResponse = post("/api/chat-rooms/sms", patientToken,
                "{\"friendshipId\":" + friendship.getFriendshipId() + "}");
        smsRoomId = roomResponse.get("data").get("roomId").asLong();
    }

    @AfterEach
    void cleanUp() {
        if (smsRoomId != null) {
            chatRoomRepository.findById(smsRoomId).ifPresent(r -> chatRoomRepository.deleteById(smsRoomId));
        }
        assignmentRepository.findByUser_UserIdAndActiveTrue(PATIENT_ID)
                .ifPresent(a -> assignmentRepository.deleteById(a.getAssignmentId()));
        friendshipRepository.findByUser_UserIdAndFriendPhone(PATIENT_ID, FRIEND_PHONE)
                .ifPresent(f -> friendshipRepository.deleteById(f.getFriendshipId()));
        userRepository.findById(PATIENT_ID).ifPresent(u -> userRepository.deleteById(PATIENT_ID));
        userRepository.findById(PATIENT_NO_ASSIGNMENT_ID).ifPresent(u -> userRepository.deleteById(PATIENT_NO_ASSIGNMENT_ID));
    }

    @Test
    void successfulSendUsesAssignedServiceNumberAsFromAndStoresDelivery() throws Exception {
        when(smsMessageSender.send(any())).thenReturn(new SmsSendResult("solapi-msg-1"));

        HttpResponse<String> response = postSms(smsRoomId, friendshipPublicKeyPatientToken, "오늘 저녁에 전화해주세요", null);
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertTrue(json.get("success").asBoolean());
        long messageId = json.get("data").get("messageId").asLong();

        verify(smsMessageSender).send(eq(new SmsOutboundMessage(SERVICE_NUMBER, FRIEND_PHONE, "오늘 저녁에 전화해주세요")));

        Optional<SmsMessageDelivery> delivery = smsMessageDeliveryRepository.findByMessage_MessageId(messageId);
        assertTrue(delivery.isPresent(), "SmsMessageDelivery 행이 저장되어야 한다.");
        assertEquals(SmsDeliveryStatus.SENT, delivery.get().getDeliveryStatus());
        assertEquals("solapi-msg-1", delivery.get().getProviderMessageId());
    }

    @Test
    void sendWithoutAssignmentReturnsReplyNumberNotAssigned() throws Exception {
        // 배정이 없는 두 번째 환자용 friendship+room을 별도로 만든다.
        Friendship secondFriendship = friendshipRepository.save(Friendship.builder()
                .user(userRepository.findById(PATIENT_NO_ASSIGNMENT_ID).orElseThrow())
                .friendUser(null).friendName("친구2").friendPhone("01099990001")
                .status("ACCEPTED").createdAt(Instant.now()).build());
        String tokenNoAssignment = jwtTokenProvider.generateAccessToken(PATIENT_NO_ASSIGNMENT_ID, UserRole.PATIENT);

        // E2EE key도 필요하므로 등록한다(그렇지 않으면 E2EE_KEY_REQUIRED가 먼저 발생해 이 테스트의 의도와 어긋난다).
        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(userRepository.findById(PATIENT_NO_ASSIGNMENT_ID).orElseThrow())
                .keyVersion(1).publicKey(Base64.getEncoder().encodeToString(publicKey))
                .createdAt(Instant.now()).build());

        JsonNode roomResponse = post("/api/chat-rooms/sms", tokenNoAssignment,
                "{\"friendshipId\":" + secondFriendship.getFriendshipId() + "}");
        long roomId = roomResponse.get("data").get("roomId").asLong();

        HttpResponse<String> response = postSms(roomId, tokenNoAssignment, "안녕하세요", null);
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(409, response.statusCode());
        assertEquals("SMS_REPLY_NUMBER_NOT_ASSIGNED", json.get("code").asText());
        verify(smsMessageSender, never()).send(any());

        chatRoomRepository.deleteById(roomId);
        friendshipRepository.deleteById(secondFriendship.getFriendshipId());
    }

    @Test
    void providerFailureReturns502ButMessageRowStillPersistsAsFailed() throws Exception {
        doThrow(new SmsSendFailedException("provider down")).when(smsMessageSender).send(any());

        HttpResponse<String> response = postSms(smsRoomId, friendshipPublicKeyPatientToken, "실패 테스트", null);
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(502, response.statusCode());
        assertEquals("SMS_SEND_FAILED", json.get("code").asText());

        // 응답은 실패지만 "발송을 시도했다"는 기록(message + FAILED delivery)은 남아야 한다(§13). 이 테스트는
        // 매번 격리된 상태에서 시작하므로(setUp에서 clean) FAILED 행이 하나라도 있으면 이번 요청의 결과다.
        boolean anyFailedDeliveryExists = smsMessageDeliveryRepository.findAll().stream()
                .anyMatch(d -> d.getDeliveryStatus() == SmsDeliveryStatus.FAILED);
        assertTrue(anyFailedDeliveryExists, "Provider 실패 시에도 FAILED 상태의 delivery 행이 남아야 한다.");
    }

    @Test
    void duplicateClientMessageIdDoesNotCallProviderTwice() throws Exception {
        when(smsMessageSender.send(any())).thenReturn(new SmsSendResult("solapi-msg-idem"));
        String clientMessageId = "cmsoit-idem-key-1";

        HttpResponse<String> first = postSms(smsRoomId, friendshipPublicKeyPatientToken, "중복 방지 테스트", clientMessageId);
        JsonNode firstJson = MAPPER.readTree(first.body());
        assertEquals(200, first.statusCode());
        long firstMessageId = firstJson.get("data").get("messageId").asLong();

        HttpResponse<String> second = postSms(smsRoomId, friendshipPublicKeyPatientToken, "중복 방지 테스트", clientMessageId);
        JsonNode secondJson = MAPPER.readTree(second.body());
        assertEquals(200, second.statusCode());
        long secondMessageId = secondJson.get("data").get("messageId").asLong();

        assertEquals(firstMessageId, secondMessageId, "동일 clientMessageId 재요청은 같은 message를 반환해야 한다.");
        verify(smsMessageSender, times(1)).send(any());
    }

    private HttpResponse<String> postSms(Long roomId, String token, String content, String clientMessageId) throws Exception {
        String clientMessageIdJson = clientMessageId == null ? "null" : "\"" + clientMessageId + "\"";
        String body = "{\"messageType\":\"TEXT\",\"content\":\"" + content + "\",\"clientMessageId\":" + clientMessageIdJson + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat-rooms/" + roomId + "/messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
