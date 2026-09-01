package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;
import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.Message;
import com.looktalk.backend.chat.entity.MessageCiphertext;
import com.looktalk.backend.chat.repository.ChatParticipantRepository;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.repository.MessageCiphertextRepository;
import com.looktalk.backend.chat.repository.MessageRepository;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsInboundProcessingStatus;
import com.looktalk.backend.sms.entity.SmsInboundReceipt;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
import com.looktalk.backend.sms.repository.SmsInboundReceiptRepository;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

// LG U+ MO inbound webhook(§19~§25) - 경로 token 인증, moNumber->Patient->moCallback->Friend 매칭,
// idempotency(moKey), plaintext 미저장, 알 수 없는 sender 자동등록 금지를 실제 HTTP + DB로 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "looktalk.sms.uplus.webhook-token=umict-test-token")
class UplusMoInboundControllerTest {

    private static final String WEBHOOK_TOKEN = "umict-test-token";
    private static final String PATIENT_ID = "umict-patient";
    private static final String PATIENT_NO_KEY_ID = "umict-patient-no-key";
    private static final String SERVICE_NUMBER = "0212349999";
    private static final String SERVICE_NUMBER_NO_KEY = "0212349998";
    private static final String FRIEND_PHONE = "01099991234";
    private static final String UNKNOWN_PHONE = "01000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserE2eeKeyRepository userE2eeKeyRepository;

    @Autowired
    private SmsInboundNumberAssignmentRepository assignmentRepository;

    @Autowired
    private SmsInboundReceiptRepository receiptRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageCiphertextRepository messageCiphertextRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatParticipantRepository chatParticipantRepository;

    private User patient;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();

        patient = userRepository.save(User.builder()
                .userId(PATIENT_ID).userEmail(PATIENT_ID + "@umict.local").userPwd("unused-in-this-test")
                .isEmailVerified(true).isSmsVerified(true).userPhone("01055556666")
                .createdAt(Instant.now()).loginId(PATIENT_ID + "-login").role(UserRole.PATIENT).build());
        userRepository.save(User.builder()
                .userId(PATIENT_NO_KEY_ID).userEmail(PATIENT_NO_KEY_ID + "@umict.local").userPwd("unused-in-this-test")
                .isEmailVerified(true).isSmsVerified(true).userPhone("01055556667")
                .createdAt(Instant.now()).loginId(PATIENT_NO_KEY_ID + "-login").role(UserRole.PATIENT).build());

        LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
        byte[] secretKey = new byte[Box.SECRETKEYBYTES];
        lazySodium.cryptoBoxKeypair(publicKey, secretKey);
        userE2eeKeyRepository.save(UserE2eeKey.builder()
                .user(patient).keyVersion(1).publicKey(Base64.getEncoder().encodeToString(publicKey))
                .createdAt(Instant.now()).build());

        friendshipRepository.save(Friendship.builder()
                .user(patient).friendUser(null).friendName("친구").friendPhone(FRIEND_PHONE)
                .status("ACCEPTED").createdAt(Instant.now()).build());
        friendshipRepository.save(Friendship.builder()
                .user(userRepository.findById(PATIENT_NO_KEY_ID).orElseThrow())
                .friendUser(null).friendName("친구").friendPhone(FRIEND_PHONE)
                .status("ACCEPTED").createdAt(Instant.now()).build());

        Instant now = Instant.now();
        assignmentRepository.save(SmsInboundNumberAssignment.builder()
                .user(patient).provider(SmsProvider.UPLUS_MO).serviceNumber(SERVICE_NUMBER)
                .active(true).activatedAt(now).createdAt(now).updatedAt(now).build());
        assignmentRepository.save(SmsInboundNumberAssignment.builder()
                .user(userRepository.findById(PATIENT_NO_KEY_ID).orElseThrow())
                .provider(SmsProvider.UPLUS_MO).serviceNumber(SERVICE_NUMBER_NO_KEY)
                .active(true).activatedAt(now).createdAt(now).updatedAt(now).build());
    }

    @AfterEach
    void cleanUp() {
        for (String userId : List.of(PATIENT_ID, PATIENT_NO_KEY_ID)) {
            userRepository.findById(userId).ifPresent(u -> {
                // chat_participant.user_id는 users 삭제 시 ON DELETE SET NULL이지만, is_withdrawn을 함께
                // true로 바꿔주지 않으면 chk_chat_participant_identity(V7) CHECK를 위반한다. 테스트가 만든
                // room은 회원탈퇴 흐름을 타지 않으므로, room 자체를 먼저 지워 참가자 행을 SET NULL이 아니라
                // CASCADE로 완전히 제거한다(각 테스트가 만들었을 수 있는 room을 빠짐없이 찾기 위해 실제
                // participation을 조회한다 - 특정 테스트 메서드 안에서만 지역적으로 room을 추적하지 않는다).
                for (ChatParticipant participation : chatParticipantRepository.findMyParticipations(userId, null)) {
                    chatRoomRepository.deleteById(participation.getRoom().getRoomId());
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
    void invalidTokenIsRejected() throws Exception {
        HttpResponse<String> response = postMo("wrong-token", singleItemBody("moKey-1", SERVICE_NUMBER, "SMSMO", FRIEND_PHONE, "테스트"));
        assertEquals(401, response.statusCode());
    }

    @Test
    void successfulInboundCreatesMessageWithoutPlaintextAndAcks() throws Exception {
        String moKey = "moKey-success-" + System.nanoTime();
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, singleItemBody(moKey, SERVICE_NUMBER, "SMSMO", FRIEND_PHONE, "네 알겠어"));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("10000", json.get("code").asText());

        SmsInboundReceipt receipt = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId()))
                .findFirst().orElseThrow();
        assertEquals(SmsInboundProcessingStatus.PROCESSED, receipt.getProcessingStatus());
        assertTrue(receipt.getMessage() != null, "처리 성공 시 receipt가 message를 가리켜야 한다.");

        Message message = messageRepository.findById(receipt.getMessage().getMessageId()).orElseThrow();
        MessageCiphertext ciphertext = messageCiphertextRepository
                .findAllByMessage_MessageIdInAndRecipientUser_UserId(List.of(message.getMessageId()), PATIENT_ID)
                .get(0);

        assertFalse(ciphertext.getCiphertext().contains("네 알겠어"), "평문이 ciphertext 컬럼에 그대로 노출되면 안 된다.");

        // 공식 moRecvDt("2024-01-29T09:22:55", Asia/Seoul)가 fallback(현재 시각)이 아니라 실제로 파싱된
        // 값으로 저장되는지 확인한다.
        Instant expectedReceivedAt = ZonedDateTime.of(2024, 1, 29, 9, 22, 55, 0, ZoneId.of("Asia/Seoul")).toInstant();
        assertEquals(expectedReceivedAt, message.getCreatedAt(), "공식 moRecvDt가 fallback 없이 정확히 파싱되어야 한다.");
    }

    @Test
    void duplicateMoKeyDoesNotCreateSecondMessage() throws Exception {
        String moKey = "moKey-dup-" + System.nanoTime();
        String body = singleItemBody(moKey, SERVICE_NUMBER, "SMSMO", FRIEND_PHONE, "중복 테스트");

        HttpResponse<String> first = postMo(WEBHOOK_TOKEN, body);
        HttpResponse<String> second = postMo(WEBHOOK_TOKEN, body);

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());

        long receiptCount = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId())).count();
        assertEquals(1, receiptCount, "동일 moKey는 receipt를 한 건만 남겨야 한다.");
    }

    @Test
    void unknownServiceNumberIsAckedButNoMessageCreated() throws Exception {
        String moKey = "moKey-unknown-number-" + System.nanoTime();
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, singleItemBody(moKey, "0299999999", "SMSMO", FRIEND_PHONE, "테스트"));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("10000", json.get("code").asText());

        SmsInboundReceipt receipt = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId())).findFirst().orElseThrow();
        assertEquals(SmsInboundProcessingStatus.REJECTED_UNKNOWN_NUMBER, receipt.getProcessingStatus());
        assertTrue(receipt.getMessage() == null);
    }

    @Test
    void unknownFriendIsAckedButNoMessageCreatedAndNoAutoFriend() throws Exception {
        String moKey = "moKey-unknown-friend-" + System.nanoTime();
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, singleItemBody(moKey, SERVICE_NUMBER, "SMSMO", UNKNOWN_PHONE, "테스트"));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("10000", json.get("code").asText());

        SmsInboundReceipt receipt = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId())).findFirst().orElseThrow();
        assertEquals(SmsInboundProcessingStatus.REJECTED_UNKNOWN_FRIEND, receipt.getProcessingStatus());

        boolean autoRegistered = friendshipRepository.findByUser_UserIdAndFriendPhone(PATIENT_ID, UNKNOWN_PHONE).isPresent();
        assertFalse(autoRegistered, "알 수 없는 발신자를 자동으로 친구 등록하면 안 된다.");
    }

    @Test
    void missingE2eeKeyIsAckedButNoMessageCreated() throws Exception {
        String moKey = "moKey-no-key-" + System.nanoTime();
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, singleItemBody(moKey, SERVICE_NUMBER_NO_KEY, "SMSMO", FRIEND_PHONE, "테스트"));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("10000", json.get("code").asText());

        SmsInboundReceipt receipt = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId())).findFirst().orElseThrow();
        assertEquals(SmsInboundProcessingStatus.REJECTED_NO_E2EE_KEY, receipt.getProcessingStatus());
    }

    @Test
    void secondReplyFromSameFriendReusesExistingRoomInsteadOfCreatingNew() throws Exception {
        String firstMoKey = "moKey-room-reuse-1-" + System.nanoTime();
        String secondMoKey = "moKey-room-reuse-2-" + System.nanoTime();

        HttpResponse<String> first = postMo(WEBHOOK_TOKEN, singleItemBody(firstMoKey, SERVICE_NUMBER, "SMSMO", FRIEND_PHONE, "첫 메시지"));
        assertEquals(200, first.statusCode());
        HttpResponse<String> second = postMo(WEBHOOK_TOKEN, singleItemBody(secondMoKey, SERVICE_NUMBER, "SMSMO", FRIEND_PHONE, "두번째 메시지"));
        assertEquals(200, second.statusCode());

        Message firstMessage = messageRepository.findById(
                receiptRepository.findAll().stream()
                        .filter(r -> firstMoKey.equals(r.getExternalMessageId())).findFirst().orElseThrow()
                        .getMessage().getMessageId()
        ).orElseThrow();
        Message secondMessage = messageRepository.findById(
                receiptRepository.findAll().stream()
                        .filter(r -> secondMoKey.equals(r.getExternalMessageId())).findFirst().orElseThrow()
                        .getMessage().getMessageId()
        ).orElseThrow();

        assertEquals(
                firstMessage.getRoom().getRoomId(), secondMessage.getRoom().getRoomId(),
                "동일 patient+friend의 두 번째 답장은 새 room이 아니라 기존 SMS room을 재사용해야 한다.");
    }

    @Test
    void unsupportedMoTypeIsAckedWithoutProcessing() throws Exception {
        String moKey = "moKey-unsupported-" + System.nanoTime();
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, singleItemBody(moKey, SERVICE_NUMBER, "MMSMO", FRIEND_PHONE, "테스트"));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("10000", json.get("code").asText());

        Optional<SmsInboundReceipt> receipt = receiptRepository.findAll().stream()
                .filter(r -> moKey.equals(r.getExternalMessageId())).findFirst();
        assertTrue(receipt.isEmpty(), "지원하지 않는 MO type은 receipt도 남기지 않는다(idempotency 대상이 아님).");
    }

    @Test
    void malformedPayloadReturnsValidationError() throws Exception {
        String body = "{\"moCnt\":1,\"moLst\":[{\"moKey\":\"\",\"moNumber\":\"" + SERVICE_NUMBER
                + "\",\"moType\":\"SMSMO\",\"moCallback\":\"" + FRIEND_PHONE + "\",\"moMsg\":\"x\",\"moRecvDt\":\"20260101000000\"}]}";
        HttpResponse<String> response = postMo(WEBHOOK_TOKEN, body);
        assertEquals(400, response.statusCode());
    }

    // moRecvDt는 LG U+ 메시지허브 MO API 공식 문서(doc.msghub.uplus.co.kr)의 실제 예시 값과 동일한
    // ISO_LOCAL_DATE_TIME 형식("2024-01-29T09:22:55")을 사용한다 - 정상 공식 payload가 fallback으로
    // 빠지지 않는지 이 형식으로 검증한다.
    private static final String OFFICIAL_MO_RECV_DT = "2024-01-29T09:22:55";

    private String singleItemBody(String moKey, String moNumber, String moType, String moCallback, String moMsg) {
        return "{\"moCnt\":1,\"moLst\":[{"
                + "\"moKey\":\"" + moKey + "\","
                + "\"moNumber\":\"" + moNumber + "\","
                + "\"moType\":\"" + moType + "\","
                + "\"moCallback\":\"" + moCallback + "\","
                + "\"moMsg\":\"" + moMsg + "\","
                + "\"moRecvDt\":\"" + OFFICIAL_MO_RECV_DT + "\""
                + "}]}";
    }

    private HttpResponse<String> postMo(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/integrations/sms/inbound/uplus/" + token))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
