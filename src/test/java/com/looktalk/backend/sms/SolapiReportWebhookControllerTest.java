package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.service.SmsMessageSender;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// SOLAPI SINGLE-REPORT webhook - secret 검증(SHA1 해시), array body, 상태 매핑(4000만 최종성공), 중복/역행
// 방지를 실제 HTTP + DB로 검증한다.
// 출처(이번 세션 solapi.com/developers/api/webhook 직접 fetch, 공식):
// - "모든 웹훅 데이터는 JSON 배열 형태로 전달됩니다."
// - X-Solapi-Secret = SHA1(등록한 secret 문자열)의 hex 값(원문 secret이 아니다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "looktalk.sms.solapi.report-webhook-secret=test-webhook-secret")
class SolapiReportWebhookControllerTest {

    private static final String PATIENT_ID = "srwct-patient";
    private static final String FRIEND_PHONE = "01099990010";
    private static final String SERVICE_NUMBER = "0212340010";
    private static final String CONFIGURED_SECRET = "test-webhook-secret";

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
    private Long deliveryId;
    private String providerMessageId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        reset(smsMessageSender);
        when(smsMessageSender.isConfigured()).thenReturn(true);
        providerMessageId = "solapi-report-" + System.nanoTime();
        // provider_message_id에는 partial UNIQUE 제약이 있으므로(V10), 여러 건을 보내는 테스트
        // (arrayWithMultipleReportsAppliesEachIndependently)에서 매 호출마다 서로 다른 값을 반환해야 한다.
        // 첫 호출은 providerMessageId를 그대로 쓰고(다른 테스트가 이 값을 참조), 이후 호출은 매번 새 값을 만든다.
        when(smsMessageSender.send(any())).thenReturn(
                new SmsSendResult(providerMessageId),
                new SmsSendResult("solapi-report-followup-" + System.nanoTime()));

        User patient = userRepository.save(User.builder()
                .userId(PATIENT_ID).userEmail(PATIENT_ID + "@srwct.local").userPwd("unused-in-this-test")
                .isEmailVerified(true).isSmsVerified(true).userPhone("01011119999")
                .createdAt(Instant.now()).loginId(PATIENT_ID + "-login").role(UserRole.PATIENT).build());

        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(patient).keyVersion(1).publicKey(Base64.getEncoder().encodeToString(publicKey))
                .createdAt(Instant.now()).build());

        Friendship friendship = friendshipRepository.save(Friendship.builder()
                .user(patient).friendUser(null).friendName("친구").friendPhone(FRIEND_PHONE)
                .status("ACCEPTED").createdAt(Instant.now()).build());

        Instant now = Instant.now();
        assignmentRepository.save(SmsInboundNumberAssignment.builder()
                .user(patient).provider(SmsProvider.UPLUS_MO).serviceNumber(SERVICE_NUMBER)
                .active(true).activatedAt(now).createdAt(now).updatedAt(now).build());

        String token = jwtTokenProvider.generateAccessToken(PATIENT_ID, UserRole.PATIENT);
        JsonNode roomResponse = post("/api/chat-rooms/sms", token, "{\"friendshipId\":" + friendship.getFriendshipId() + "}");
        smsRoomId = roomResponse.get("data").get("roomId").asLong();

        JsonNode sendResponse = post("/api/chat-rooms/" + smsRoomId + "/messages", token,
                "{\"messageType\":\"TEXT\",\"content\":\"report test\"}");
        long messageId = sendResponse.get("data").get("messageId").asLong();
        deliveryId = smsMessageDeliveryRepository.findByMessage_MessageId(messageId).orElseThrow().getSmsDeliveryId();
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
    }

    @Test
    void correctHashedSecretIsAccepted() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(200, response.statusCode());
    }

    @Test
    void rawSecretInHeaderIsRejected() throws Exception {
        // X-Solapi-Secret은 SHA1 해시값이지 원문 secret이 아니다 - 원문을 그대로 보내면 거부되어야 한다.
        HttpResponse<String> response = postReport(
                CONFIGURED_SECRET, "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(401, response.statusCode());
    }

    @Test
    void invalidHashIsRejected() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex("wrong-secret"), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(401, response.statusCode());
    }

    @Test
    void missingSecretHeaderIsRejected() throws Exception {
        HttpResponse<String> response = postReport(null, "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(401, response.statusCode());
    }

    // 접수 단계(2000)는 최종 상태가 아니므로 SENT를 유지해야 한다(DELIVERED도 FAILED도 아님) - 그래야 나중에
    // 4000이 와도 이미 최종 상태로 잠겨 DELIVERED로 못 바뀌는 문제가 생기지 않는다.
    @Test
    void acceptedStatusCode2000KeepsSent() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "2000", "정상 접수(이통사로 접수 예정)");

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.SENT, delivery.getDeliveryStatus(), "2000은 SENT를 유지해야 한다(최종 상태 아님).");
    }

    // 이통사 처리 중(3000)도 마찬가지로 최종 상태가 아니다.
    @Test
    void processingStatusCode3000KeepsSent() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "3000", "이통사로 접수(리포트를 기다리는 중)");

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.SENT, delivery.getDeliveryStatus(), "3000은 SENT를 유지해야 한다(최종 상태 아님).");
    }

    // 2000(SENT 유지) 이후에 4000이 도착하면 정상적으로 DELIVERED로 전이되어야 한다 - 2000을 FAILED로 확정해
    // 버리면 이 전이가 막히는 것이 이번에 고친 핵심 버그였다.
    @Test
    void sentCanTransitionToDeliveredAfter2000() throws Exception {
        assertEquals(200, postReport(sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "2000", "정상 접수")
                .statusCode());
        SmsMessageDelivery afterAccepted = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.SENT, afterAccepted.getDeliveryStatus());

        HttpResponse<String> finalReport = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(200, finalReport.statusCode());

        SmsMessageDelivery afterDelivered = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.DELIVERED, afterDelivered.getDeliveryStatus());
    }

    @Test
    void finalSuccessCode4000MarksDelivered() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신자에게 정상 전달된 상태입니다.");

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.DELIVERED, delivery.getDeliveryStatus());
        assertNotNull(delivery.getDeliveredAt());
    }

    // SOLAPI dateReceived(단말 수신 시각, 공식 문서 확인)가 있으면 deliveredAt에 그대로 사용한다.
    @Test
    void deliveredAtUsesOfficialDateReceivedWhenPresent() throws Exception {
        String dateReceived = "2022-12-29T02:18:44.000Z";
        HttpResponse<String> response = postReportWithDateReceived(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료", dateReceived);

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(Instant.parse(dateReceived), delivery.getDeliveredAt());
    }

    // dateReceived가 없거나 파싱할 수 없으면 webhook 처리 시각(Instant.now())으로 안전하게 대체한다.
    @Test
    void deliveredAtFallsBackToNowWhenDateReceivedInvalid() throws Exception {
        Instant before = Instant.now();
        HttpResponse<String> response = postReportWithDateReceived(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료", "not-a-date");
        Instant after = Instant.now();

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertTrue(!delivery.getDeliveredAt().isBefore(before) && !delivery.getDeliveredAt().isAfter(after));
    }

    @Test
    void carrierFailureCodeMarksFailed() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "3040", "미가입자");

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.FAILED, delivery.getDeliveryStatus());
        assertNull(delivery.getDeliveredAt(), "FAILED에는 deliveredAt을 설정하지 않는다.");
    }

    // 배열 안에 알 수 없는 provider messageId 항목이 섞여 있어도 요청 전체를 실패시키지 않는다.
    @Test
    void unknownProviderMessageIdIsSkippedWithoutFailingWholeRequest() throws Exception {
        String body = "[" + reportItemJson("no-such-message-id", "4000", "ok") + "]";
        HttpResponse<String> response = postReportBody(sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", body);

        assertEquals(200, response.statusCode());
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.SENT, delivery.getDeliveryStatus(), "알 수 없는 항목은 기존 상태에 영향을 주면 안 된다.");
    }

    @Test
    void groupReportEventIsIgnoredWithoutError() throws Exception {
        HttpResponse<String> response = postReport(
                sha1Hex(CONFIGURED_SECRET), "GROUP-REPORT", providerMessageId, "4000", "ok");

        assertEquals(200, response.statusCode());
        // GROUP-REPORT는 이 handler가 처리하지 않으므로 상태 변화가 없어야 한다(여전히 SENT).
        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.SENT, delivery.getDeliveryStatus());
    }

    @Test
    void duplicateReportAndStatusRegressionAreBothPrevented() throws Exception {
        assertEquals(200, postReport(sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료")
                .statusCode());

        // 동일 SINGLE-REPORT를 다시 수신해도 idempotent해야 한다(에러 없이 200, 상태는 그대로 DELIVERED).
        HttpResponse<String> duplicate = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "4000", "수신 완료");
        assertEquals(200, duplicate.statusCode());

        // 이미 최종 상태(DELIVERED)인데 나중에 실패 코드가 와도 역행하지 않아야 한다.
        HttpResponse<String> lateFailure = postReport(
                sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", providerMessageId, "3040", "늦은 실패");
        assertEquals(200, lateFailure.statusCode());

        SmsMessageDelivery delivery = smsMessageDeliveryRepository.findById(deliveryId).orElseThrow();
        assertEquals(SmsDeliveryStatus.DELIVERED, delivery.getDeliveryStatus(), "최종 상태는 역행하면 안 된다.");
    }

    @Test
    void arrayWithMultipleReportsAppliesEachIndependently() throws Exception {
        // 두 번째 delivery를 하나 더 만들어 배열 하나에 서로 다른 결과의 report 2건을 함께 보낸다.
        String token = jwtTokenProvider.generateAccessToken(PATIENT_ID, UserRole.PATIENT);
        JsonNode secondSend = post("/api/chat-rooms/" + smsRoomId + "/messages", token,
                "{\"messageType\":\"TEXT\",\"content\":\"두 번째 report 테스트\"}");
        long secondMessageId = secondSend.get("data").get("messageId").asLong();
        long secondDeliveryId = smsMessageDeliveryRepository.findByMessage_MessageId(secondMessageId).orElseThrow()
                .getSmsDeliveryId();
        // 두 번째 발송의 providerMessageId도 mock이 동일 인스턴스를 반환하므로 실제로는 첫 번째와 같은 값이 될 수
        // 있다 - 서로 다른 값을 쓰도록 두 번째 messageId 조회 후 delivery row를 직접 다른 provider messageId로
        // 갱신한다.
        String secondProviderMessageId = "solapi-report-second-" + System.nanoTime();
        SmsMessageDelivery secondDelivery = smsMessageDeliveryRepository.findById(secondDeliveryId).orElseThrow();
        secondDelivery.setProviderMessageId(secondProviderMessageId);
        smsMessageDeliveryRepository.save(secondDelivery);

        String body = "[" + reportItemJson(providerMessageId, "4000", "수신 완료") + ","
                + reportItemJson(secondProviderMessageId, "3040", "미가입자") + "]";
        HttpResponse<String> response = postReportBody(sha1Hex(CONFIGURED_SECRET), "SINGLE-REPORT", body);

        assertEquals(200, response.statusCode());
        assertEquals(SmsDeliveryStatus.DELIVERED,
                smsMessageDeliveryRepository.findById(deliveryId).orElseThrow().getDeliveryStatus());
        assertEquals(SmsDeliveryStatus.FAILED,
                smsMessageDeliveryRepository.findById(secondDeliveryId).orElseThrow().getDeliveryStatus());
    }

    private String reportItemJson(String messageId, String statusCode, String statusMessage) {
        return reportItemJson(messageId, statusCode, statusMessage, null);
    }

    private String reportItemJson(String messageId, String statusCode, String statusMessage, String dateReceived) {
        String dateReceivedField = dateReceived == null ? "" : ",\"dateReceived\":\"" + dateReceived + "\"";
        return "{\"messageId\":\"" + messageId + "\",\"statusCode\":\"" + statusCode
                + "\",\"statusMessage\":\"" + statusMessage + "\"" + dateReceivedField + "}";
    }

    private HttpResponse<String> postReport(
            String secretHeaderValue, String eventName, String messageId, String statusCode, String statusMessage
    ) throws Exception {
        String body = "[" + reportItemJson(messageId, statusCode, statusMessage) + "]";
        return postReportBody(secretHeaderValue, eventName, body);
    }

    private HttpResponse<String> postReportWithDateReceived(
            String secretHeaderValue, String eventName, String messageId, String statusCode,
            String statusMessage, String dateReceived
    ) throws Exception {
        String body = "[" + reportItemJson(messageId, statusCode, statusMessage, dateReceived) + "]";
        return postReportBody(secretHeaderValue, eventName, body);
    }

    private HttpResponse<String> postReportBody(String secretHeaderValue, String eventName, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/integrations/sms/reports/solapi"))
                .header("Content-Type", "application/json")
                .header("X-Solapi-Event-Name", eventName)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secretHeaderValue != null) {
            builder.header("X-Solapi-Secret", secretHeaderValue);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String sha1Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode post(String path, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }
}
