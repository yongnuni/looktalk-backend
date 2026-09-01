package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
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
import com.looktalk.backend.chat.service.SmsSendResult;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 이번 조사(단방향 로컬 데모) - 환자에게 U+ MO 번호(sms_inbound_number_assignment)가 없어도 SOLAPI에 실제
// 등록된 번호를 발신번호로 대신 쓸 수 있게 하는 local/demo fallback 정책을 검증한다. production 기본값(false)은
// ChatMessageServiceSmsOutboundIntegrationTest가 계속 검증하므로, 이 클래스는 fallback이 명시적으로 켜졌을 때의
// 동작만 별도 Spring context(TestPropertySource)로 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "looktalk.sms.chat-sender-fallback.enabled=true",
        "looktalk.sms.solapi.chat-sender-number=0700000000"
})
class ChatMessageServiceSmsChatSenderFallbackTest {

    private static final String PATIENT_NO_ASSIGNMENT_ID = "cmscsft-patient-no-assign";
    private static final String PATIENT_WITH_ASSIGNMENT_ID = "cmscsft-patient-with-assign";
    private static final String FRIEND_PHONE = "01099990000";
    private static final String ASSIGNED_SERVICE_NUMBER = "0212345678";
    private static final String FALLBACK_SENDER_NUMBER = "0700000000";

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
    private ChatRoomRepository chatRoomRepository;

    @MockitoBean
    private SmsMessageSender smsMessageSender;

    @BeforeEach
    void setUp() {
        cleanUp();
        reset(smsMessageSender);
        when(smsMessageSender.isConfigured()).thenReturn(true);
        when(smsMessageSender.send(any())).thenReturn(new SmsSendResult("solapi-fallback-msg"));
    }

    @AfterEach
    void cleanUp() {
        for (String userId : java.util.List.of(PATIENT_NO_ASSIGNMENT_ID, PATIENT_WITH_ASSIGNMENT_ID)) {
            userRepository.findById(userId).ifPresent(u -> {
                for (var room : chatRoomRepository.findAll()) {
                    // 이 테스트 클래스가 만든 room만 대상으로 한다(다른 테스트 클래스 데이터와 섞이지 않도록
                    // conversation_key 접두어로 좁힌다).
                    if (room.getConversationKey() != null && room.getConversationKey().contains(userId)) {
                        chatRoomRepository.deleteById(room.getRoomId());
                    }
                }
                friendshipRepository.findByUser_UserIdAndFriendPhone(userId, FRIEND_PHONE)
                        .ifPresent(f -> friendshipRepository.deleteById(f.getFriendshipId()));
                assignmentRepository.findByUser_UserIdAndActiveTrue(userId)
                        .ifPresent(a -> assignmentRepository.deleteById(a.getAssignmentId()));
                userRepository.deleteById(userId);
            });
        }
    }

    @Test
    void noAssignmentButFallbackEnabledUsesFallbackSenderNumber() throws Exception {
        User patient = createPatientWithKey(PATIENT_NO_ASSIGNMENT_ID);
        Friendship friendship = friendshipRepository.save(Friendship.builder()
                .user(patient).friendUser(null).friendName("친구")
                .friendPhone(FRIEND_PHONE).status("ACCEPTED").createdAt(Instant.now()).build());
        String token = jwtTokenProvider.generateAccessToken(PATIENT_NO_ASSIGNMENT_ID, UserRole.PATIENT);

        JsonNode roomResponse = post("/api/chat-rooms/sms", token,
                "{\"friendshipId\":" + friendship.getFriendshipId() + "}");
        long roomId = roomResponse.get("data").get("roomId").asLong();

        HttpResponse<String> response = postSms(roomId, token, "오늘 어때?");
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode(), json.toString());
        verify(smsMessageSender).send(eq(new SmsOutboundMessage(FALLBACK_SENDER_NUMBER, FRIEND_PHONE, "오늘 어때?")));
    }

    @Test
    void assignmentPresentTakesPriorityOverFallback() throws Exception {
        User patient = createPatientWithKey(PATIENT_WITH_ASSIGNMENT_ID);
        Friendship friendship = friendshipRepository.save(Friendship.builder()
                .user(patient).friendUser(null).friendName("친구")
                .friendPhone(FRIEND_PHONE).status("ACCEPTED").createdAt(Instant.now()).build());
        Instant now = Instant.now();
        assignmentRepository.save(SmsInboundNumberAssignment.builder()
                .user(patient).provider(SmsProvider.UPLUS_MO).serviceNumber(ASSIGNED_SERVICE_NUMBER)
                .active(true).activatedAt(now).createdAt(now).updatedAt(now).build());
        String token = jwtTokenProvider.generateAccessToken(PATIENT_WITH_ASSIGNMENT_ID, UserRole.PATIENT);

        JsonNode roomResponse = post("/api/chat-rooms/sms", token,
                "{\"friendshipId\":" + friendship.getFriendshipId() + "}");
        long roomId = roomResponse.get("data").get("roomId").asLong();

        HttpResponse<String> response = postSms(roomId, token, "오늘 어때?");
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode(), json.toString());
        verify(smsMessageSender).send(eq(new SmsOutboundMessage(ASSIGNED_SERVICE_NUMBER, FRIEND_PHONE, "오늘 어때?")));
    }

    private User createPatientWithKey(String userId) throws Exception {
        User patient = userRepository.save(User.builder()
                .userId(userId)
                .userEmail(userId + "@cmscsft.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(true)
                .userPhone("01011110000")
                .createdAt(Instant.now())
                .loginId(userId + "-login")
                .role(UserRole.PATIENT)
                .build());

        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(patient).keyVersion(1).publicKey(Base64.getEncoder().encodeToString(publicKey))
                .createdAt(Instant.now()).build());

        return patient;
    }

    private HttpResponse<String> postSms(Long roomId, String token, String content) throws Exception {
        String body = "{\"messageType\":\"TEXT\",\"content\":\"" + content + "\"}";
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
