package com.looktalk.backend.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.ChatRoom;
import com.looktalk.backend.chat.entity.ChatRoomType;
import com.looktalk.backend.chat.entity.Message;
import com.looktalk.backend.chat.entity.MessageCiphertext;
import com.looktalk.backend.chat.repository.ChatParticipantRepository;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.repository.MessageCiphertextRepository;
import com.looktalk.backend.chat.repository.MessageRepository;
import com.looktalk.backend.e2ee.service.EncryptedPayloadValidator;
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

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md Step 8B - CHAT-001/002/005에 적용한
// recipient(viewer)별 patientAlias 표시 규칙을 실제 서버(랜덤 포트)와 실제 DB로 고정한다.
// 병원 채팅 권한/대상 범위(모든 내부 회원)는 이 테스트에서 전혀 변경하지 않는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatDisplayNameIntegrationTest {

    private static final String STAFF_A = "cdn-staff-a";
    private static final String STAFF_B = "cdn-staff-b";
    private static final String PATIENT_ALIASED = "cdn-patient-aliased";
    private static final String PATIENT_UNASSIGNED = "cdn-patient-unassigned";
    private static final List<String> ALL_TEST_USER_IDS =
            List.of(STAFF_A, STAFF_B, PATIENT_ALIASED, PATIENT_UNASSIGNED);
    private static final String ALIAS = "101호_김철수";

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
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatParticipantRepository chatParticipantRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageCiphertextRepository messageCiphertextRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Long hospitalId;
    private Long roomWithStaffA;
    private Long roomWithStaffB;

    @BeforeEach
    void setUp() {
        cleanUpTestData();

        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .hospitalName("CDN Hospital")
                .inviteCode("CDN-INVITE-" + System.nanoTime())
                .createdAt(Instant.now())
                .build());
        hospitalId = hospital.getHospitalId();

        User staffUserA = userRepository.save(staffUser(STAFF_A));
        User staffUserB = userRepository.save(staffUser(STAFF_B));
        User patientAliased = userRepository.save(patient(PATIENT_ALIASED, "김철수"));
        userRepository.save(patient(PATIENT_UNASSIGNED, "이영희"));

        Staff staffA = staffRepository.save(Staff.builder()
                .user(staffUserA).hospital(hospital).createdAt(Instant.now()).build());
        staffRepository.save(Staff.builder()
                .user(staffUserB).hospital(hospital).createdAt(Instant.now()).build());

        // STAFF_A만 PATIENT_ALIASED의 담당 STAFF다. STAFF_B는 별도 assignment가 없다(alias 미적용 케이스).
        assignmentRepository.save(StaffPatientAssignment.builder()
                .staff(staffA).patientUser(patientAliased).patientAlias(ALIAS).assignedAt(Instant.now()).build());

        // room1: STAFF_A <-> PATIENT_ALIASED. room2: STAFF_B <-> PATIENT_ALIASED. 두 room 모두
        // PATIENT_ALIASED가 sender인 message를 하나씩 갖는다(CHAT-005 sender 표시명 테스트용).
        ChatRoom room1 = createHospitalRoom(STAFF_A, PATIENT_ALIASED);
        roomWithStaffA = room1.getRoomId();
        ChatParticipant patientInRoom1 = chatParticipantRepository.findAllByRoomIdIn(List.of(room1.getRoomId()))
                .stream().filter(p -> p.getUser() != null && PATIENT_ALIASED.equals(p.getUser().getUserId()))
                .findFirst().orElseThrow();
        saveMessage(room1, patientInRoom1, staffUserA);

        ChatRoom room2 = createHospitalRoom(STAFF_B, PATIENT_ALIASED);
        roomWithStaffB = room2.getRoomId();
        ChatParticipant patientInRoom2 = chatParticipantRepository.findAllByRoomIdIn(List.of(room2.getRoomId()))
                .stream().filter(p -> p.getUser() != null && PATIENT_ALIASED.equals(p.getUser().getUserId()))
                .findFirst().orElseThrow();
        saveMessage(room2, patientInRoom2, staffUserB);
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
    }

    private ChatRoom createHospitalRoom(String internalUserId1, String internalUserId2) {
        User user1 = userRepository.findById(internalUserId1).orElseThrow();
        User user2 = userRepository.findById(internalUserId2).orElseThrow();

        ChatRoom room = chatRoomRepository.save(ChatRoom.builder()
                .roomType(ChatRoomType.HOSPITAL)
                .conversationKey("HOSPITAL:" + internalUserId1 + ":" + internalUserId2)
                .createdAt(Instant.now())
                .build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(user1).joinedAt(Instant.now()).withdrawn(false).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(user2).joinedAt(Instant.now()).withdrawn(false).build());
        return room;
    }

    // 실제 CHAT-006 전송처럼 sender 본인용 ciphertext도 함께 저장한다(sender가 자기 message를 다시 조회할 수
    // 있어야 self-view 테스트가 가능하다).
    private void saveMessage(ChatRoom room, ChatParticipant sender, User recipient) {
        Message message = messageRepository.save(Message.builder()
                .room(room).senderParticipant(sender).messageType("TEXT").createdAt(Instant.now()).build());
        messageCiphertextRepository.save(MessageCiphertext.builder()
                .message(message)
                .recipientUser(recipient)
                .keyVersion(1)
                .encryptionAlgorithm(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX)
                .ciphertext("cdn-ciphertext-for-" + recipient.getUserId())
                .build());
        messageCiphertextRepository.save(MessageCiphertext.builder()
                .message(message)
                .recipientUser(sender.getUser())
                .keyVersion(1)
                .encryptionAlgorithm(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX)
                .ciphertext("cdn-ciphertext-for-" + sender.getUser().getUserId())
                .build());
    }

    private User patient(String userId, String userName) {
        return User.builder()
                .userId(userId)
                .userName(userName)
                .userEmail(userId + "@cdn.local")
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
                .userEmail(userId + "@cdn.local")
                .userPwd("unused-in-this-test")
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(Instant.now())
                .loginId(userId + "-login")
                .role(UserRole.STAFF)
                .build();
    }

    private void cleanUpTestData() {
        chatRoomRepository.findByConversationKey("HOSPITAL:" + STAFF_A + ":" + PATIENT_ALIASED)
                .ifPresent(room -> chatRoomRepository.deleteById(room.getRoomId()));
        chatRoomRepository.findByConversationKey("HOSPITAL:" + STAFF_B + ":" + PATIENT_ALIASED)
                .ifPresent(room -> chatRoomRepository.deleteById(room.getRoomId()));
        for (String userId : ALL_TEST_USER_IDS) {
            userRepository.findById(userId).ifPresent(u -> userRepository.deleteById(userId));
        }
        if (hospitalId != null) {
            hospitalRepository.findById(hospitalId).ifPresent(h -> hospitalRepository.deleteById(hospitalId));
            hospitalId = null;
        }
    }

    // ===== CHAT-001 GET /api/chat-contacts =====

    @Test
    void contactsShowAliasToAssignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/chat-contacts", token);

        JsonNode row = findContactRow(result.body(), PATIENT_ALIASED);
        assertEquals(ALIAS, row.get("displayName").asText());
    }

    @Test
    void contactsShowPlainUserNameToUnassignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        HttpResult result = get("/api/chat-contacts", token);

        JsonNode row = findContactRow(result.body(), PATIENT_ALIASED);
        assertEquals("김철수", row.get("displayName").asText());
    }

    @Test
    void contactsNeverShowAliasToPatientViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(PATIENT_UNASSIGNED, UserRole.PATIENT);

        HttpResult result = get("/api/chat-contacts", token);

        JsonNode row = findContactRow(result.body(), PATIENT_ALIASED);
        assertEquals("김철수", row.get("displayName").asText(),
                "PATIENT viewer는 다른 STAFF가 설정한 alias를 절대 볼 수 없어야 한다.");
    }

    @Test
    void unassignedPatientStillAppearsInContacts() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/chat-contacts", token);

        JsonNode row = findContactRow(result.body(), PATIENT_UNASSIGNED);
        assertEquals("이영희", row.get("displayName").asText(),
                "담당 관계가 없어도 채팅 대상 범위는 변경되지 않아야 한다(회귀 확인).");
    }

    // ===== CHAT-002 GET /api/chat-rooms =====

    @Test
    void roomListShowsAliasToAssignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/chat-rooms?type=HOSPITAL", token);

        JsonNode room = findRoomRow(result.body(), roomWithStaffA);
        assertEquals(ALIAS, room.get("target").get("displayName").asText());
    }

    @Test
    void roomListShowsPlainNameToUnassignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        HttpResult result = get("/api/chat-rooms?type=HOSPITAL", token);

        JsonNode room = findRoomRow(result.body(), roomWithStaffB);
        assertEquals("김철수", room.get("target").get("displayName").asText());
    }

    @Test
    void roomListAliasChangeIsReflectedLiveWithoutSnapshotting() throws Exception {
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult before = get("/api/chat-rooms?type=HOSPITAL", staffToken);
        assertEquals(ALIAS, findRoomRow(before.body(), roomWithStaffA).get("target").get("displayName").asText());

        patch("/api/staff/me/patients/" + PATIENT_ALIASED + "/alias", staffToken, "{\"alias\":\"변경된_별칭\"}");

        HttpResult after = get("/api/chat-rooms?type=HOSPITAL", staffToken);
        assertEquals("변경된_별칭", findRoomRow(after.body(), roomWithStaffA).get("target").get("displayName").asText(),
                "room/message DB row에 alias를 snapshot하지 않고 매 조회마다 다시 계산해야 한다.");
    }

    // ===== CHAT-005 GET /api/chat-rooms/{roomId}/messages =====

    @Test
    void historyShowsAliasToAssignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult result = get("/api/chat-rooms/" + roomWithStaffA + "/messages", token);

        JsonNode message = result.body().get("data").get("messages").get(0);
        assertEquals(ALIAS, message.get("senderDisplayName").asText());
    }

    @Test
    void historyShowsPlainNameToUnassignedStaffViewer() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(STAFF_B, UserRole.STAFF);

        HttpResult result = get("/api/chat-rooms/" + roomWithStaffB + "/messages", token);

        JsonNode message = result.body().get("data").get("messages").get(0);
        assertEquals("김철수", message.get("senderDisplayName").asText());
    }

    @Test
    void historyAliasChangeRetroactivelyAffectsSameMessageOnReQuery() throws Exception {
        String staffToken = jwtTokenProvider.generateAccessToken(STAFF_A, UserRole.STAFF);

        HttpResult before = get("/api/chat-rooms/" + roomWithStaffA + "/messages", staffToken);
        long messageId = before.body().get("data").get("messages").get(0).get("messageId").asLong();
        assertEquals(ALIAS, before.body().get("data").get("messages").get(0).get("senderDisplayName").asText());

        patch("/api/staff/me/patients/" + PATIENT_ALIASED + "/alias", staffToken, "{\"alias\":\"변경된_별칭\"}");

        HttpResult after = get("/api/chat-rooms/" + roomWithStaffA + "/messages", staffToken);
        JsonNode sameMessage = after.body().get("data").get("messages").get(0);
        assertEquals(messageId, sameMessage.get("messageId").asLong(), "같은 message여야 한다.");
        assertEquals("변경된_별칭", sameMessage.get("senderDisplayName").asText(),
                "과거 message row에 alias 문자열을 저장(snapshot)하지 않고 조회 시점에 다시 계산해야 한다.");
    }

    @Test
    void selfViewNeverShowsOwnAliasEvenWhenSetByStaff() throws Exception {
        // PATIENT_ALIASED 본인이 자기가 보낸 message를 조회한다. STAFF_A가 자신에게 alias를 설정해 두었더라도
        // 본인 시점(viewer == target)에는 항상 전역 규칙(userName -> loginId)만 보여야 한다.
        String token = jwtTokenProvider.generateAccessToken(PATIENT_ALIASED, UserRole.PATIENT);

        HttpResult result = get("/api/chat-rooms/" + roomWithStaffA + "/messages", token);

        JsonNode message = result.body().get("data").get("messages").get(0);
        assertEquals("김철수", message.get("senderDisplayName").asText());
    }

    // ===== helpers =====

    private JsonNode findContactRow(JsonNode responseBody, String userId) {
        for (JsonNode row : responseBody.get("data").get("content")) {
            if (userId.equals(row.get("userId").asText())) {
                return row;
            }
        }
        throw new AssertionError("contact row not found for " + userId + ": " + responseBody);
    }

    private JsonNode findRoomRow(JsonNode responseBody, Long roomId) {
        for (JsonNode row : responseBody.get("data")) {
            if (roomId.equals(row.get("roomId").asLong())) {
                return row;
            }
        }
        throw new AssertionError("room row not found for " + roomId + ": " + responseBody);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResult get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), MAPPER.readTree(response.body()));
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

    private record HttpResult(int status, JsonNode body) {
    }
}
