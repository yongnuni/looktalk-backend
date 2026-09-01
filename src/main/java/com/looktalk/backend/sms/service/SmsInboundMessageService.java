package com.looktalk.backend.sms.service;

import com.looktalk.backend.chat.dto.ChatMessageCreatedEventData;
import com.looktalk.backend.chat.dto.ChatRoomCreateResponse;
import com.looktalk.backend.chat.dto.ChatWebSocketEvent;
import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.Message;
import com.looktalk.backend.chat.entity.MessageCiphertext;
import com.looktalk.backend.chat.repository.ChatParticipantRepository;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.repository.MessageCiphertextRepository;
import com.looktalk.backend.chat.repository.MessageRepository;
import com.looktalk.backend.chat.service.ChatRoomService;
import com.looktalk.backend.chat.websocket.ChatRealtimePublisher;
import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.e2ee.service.EncryptedPayloadValidator;
import com.looktalk.backend.e2ee.service.SealedBoxEncryptor;
import com.looktalk.backend.sms.dto.UplusMoWebhookRequest.MoItem;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsInboundProcessingStatus;
import com.looktalk.backend.sms.entity.SmsInboundReceipt;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
import com.looktalk.backend.sms.repository.SmsInboundReceiptRepository;
import com.looktalk.backend.user.entity.Friendship;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.repository.FriendshipRepository;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.PhoneNormalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// docs/LookTalk_Backend_API_Spec_v2.1.md §5.7, §59 및 이번 조사(§22)를 기준으로 한 U+ MO inbound 처리.
// "moNumber(서비스 수신번호) -> Patient -> moCallback(발신 전화번호)으로 Friend 확인 -> SMS room -> Message"
// 순서를 강제한다. 전화번호 하나만으로 전체 사용자를 조회하는 경로는 두지 않는다(항상 moNumber로 Patient를
// 먼저 확정한 뒤 그 Patient 스코프 안에서만 Friend를 찾는다).
@Service
public class SmsInboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundMessageService.class);
    private static final String SUPPORTED_MO_TYPE = "SMSMO";

    private final SmsInboundNumberAssignmentRepository assignmentRepository;
    private final SmsInboundReceiptRepository receiptRepository;
    private final FriendshipRepository friendshipRepository;
    private final ChatRoomService chatRoomService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final MessageRepository messageRepository;
    private final MessageCiphertextRepository messageCiphertextRepository;
    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final SealedBoxEncryptor sealedBoxEncryptor;
    private final ChatRealtimePublisher realtimePublisher;
    private final TransactionTemplate transactionTemplate;

    public SmsInboundMessageService(
            SmsInboundNumberAssignmentRepository assignmentRepository,
            SmsInboundReceiptRepository receiptRepository,
            FriendshipRepository friendshipRepository,
            ChatRoomService chatRoomService,
            ChatRoomRepository chatRoomRepository,
            ChatParticipantRepository chatParticipantRepository,
            MessageRepository messageRepository,
            MessageCiphertextRepository messageCiphertextRepository,
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            SealedBoxEncryptor sealedBoxEncryptor,
            ChatRealtimePublisher realtimePublisher,
            PlatformTransactionManager transactionManager
    ) {
        this.assignmentRepository = assignmentRepository;
        this.receiptRepository = receiptRepository;
        this.friendshipRepository = friendshipRepository;
        this.chatRoomService = chatRoomService;
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.messageRepository = messageRepository;
        this.messageCiphertextRepository = messageCiphertextRepository;
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.sealedBoxEncryptor = sealedBoxEncryptor;
        this.realtimePublisher = realtimePublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // moLst의 항목 1건을 처리한다. 예외를 던지면(transient 취급) 호출부(Controller)가 U+에 재시도를 유도하는
    // 응답을 보낸다 - 그래서 "영구적으로 재시도해도 달라지지 않는" 케이스는 여기서 절대 예외를 던지지 않고
    // Outcome으로 명시적으로 구분해 반환한다(§24).
    public Outcome processMoItem(MoItem item) {
        if (!SUPPORTED_MO_TYPE.equals(item.moType())) {
            // MMSMO/RCSMO 등은 이번 범위에서 실제 친구채팅으로 처리하지 않는다(§2). 재시도해도 달라지지 않으므로
            // permanent reject와 동일하게 성공 ACK로 응답하되 receipt는 남기지 않는다(중복 처리 대상이 아니다).
            log.info("지원하지 않는 MO type을 수신해 무시합니다. moType={}", item.moType());
            return Outcome.unsupportedType();
        }

        String moKey = item.moKey();
        if (receiptRepository.existsByProviderAndExternalMessageId(SmsProvider.UPLUS_MO, moKey)) {
            return Outcome.alreadyProcessed();
        }

        String moNumber = PhoneNormalizer.normalize(item.moNumber());
        String moCallback = PhoneNormalizer.normalize(item.moCallback());
        Instant receivedAt = parseMoRecvDt(item.moRecvDt());

        ProcessResult result = transactionTemplate.execute(status -> processWithinTransaction(
                moKey, moNumber, moCallback, item.moMsg(), receivedAt));

        if (result.published() != null) {
            realtimePublisher.publishMessageCreated(result.published().recipientUserId(), result.published().event());
        }
        return Outcome.processed(result.status());
    }

    private ProcessResult processWithinTransaction(
            String moKey, String moNumber, String moCallback, String moMsg, Instant receivedAt
    ) {
        Optional<SmsInboundNumberAssignment> assignment =
                assignmentRepository.findByServiceNumberAndActiveTrue(moNumber);
        if (assignment.isEmpty()) {
            saveReceipt(moKey, moNumber, moCallback, receivedAt, null, SmsInboundProcessingStatus.REJECTED_UNKNOWN_NUMBER);
            return ProcessResult.rejected(SmsInboundProcessingStatus.REJECTED_UNKNOWN_NUMBER);
        }
        String patientUserId = assignment.get().getUser().getUserId();

        Optional<Friendship> friendship = friendshipRepository.findByUser_UserIdAndFriendPhone(patientUserId, moCallback);
        if (friendship.isEmpty()) {
            // 알 수 없는 사람이 서비스 번호로 SMS를 보냈다고 해서 자동으로 환자의 친구로 등록하지 않는다.
            saveReceipt(moKey, moNumber, moCallback, receivedAt, null, SmsInboundProcessingStatus.REJECTED_UNKNOWN_FRIEND);
            return ProcessResult.rejected(SmsInboundProcessingStatus.REJECTED_UNKNOWN_FRIEND);
        }

        Optional<UserE2eeKey> activeKey = userE2eeKeyRepository
                .findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(patientUserId);
        if (activeKey.isEmpty()) {
            saveReceipt(moKey, moNumber, moCallback, receivedAt, null, SmsInboundProcessingStatus.REJECTED_NO_E2EE_KEY);
            return ProcessResult.rejected(SmsInboundProcessingStatus.REJECTED_NO_E2EE_KEY);
        }

        // 기존 ChatRoomService.findOrCreateSmsRoom()을 그대로 재사용한다(§22 step 12).
        ChatRoomService.FindOrCreateResult roomResult =
                chatRoomService.findOrCreateSmsRoom(patientUserId, friendship.get().getFriendshipId());
        ChatRoomCreateResponse roomResponse = roomResult.room();

        List<ChatParticipant> participants =
                chatParticipantRepository.findAllByRoomIdIn(List.of(roomResponse.roomId()));
        ChatParticipant externalParticipant = participants.stream()
                .filter(p -> p.getUser() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "room " + roomResponse.roomId() + "은 SMS room인데 외부 participant가 없습니다."));

        User patient = userRepository.getReferenceById(patientUserId);
        String ciphertext = sealedBoxEncryptor.encrypt(moMsg, activeKey.get().getPublicKey());

        Message message = Message.builder()
                .room(chatRoomRepository.getReferenceById(roomResponse.roomId()))
                .senderParticipant(externalParticipant)
                .messageType("TEXT")
                .createdAt(receivedAt)
                .build();
        messageRepository.save(message);

        messageCiphertextRepository.save(MessageCiphertext.builder()
                .message(message)
                .recipientUser(patient)
                .keyVersion(activeKey.get().getKeyVersion())
                .encryptionAlgorithm(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX)
                .ciphertext(ciphertext)
                .build());

        saveReceipt(moKey, moNumber, moCallback, receivedAt, message.getMessageId(), SmsInboundProcessingStatus.PROCESSED);

        ChatMessageCreatedEventData eventData = new ChatMessageCreatedEventData(
                message.getMessageId(),
                externalParticipant.getParticipantId(),
                null,
                externalParticipant.getExternalName(),
                new EncryptedPayload(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX, activeKey.get().getKeyVersion(), ciphertext),
                message.getCreatedAt()
        );
        PublishTarget publishTarget = new PublishTarget(
                patientUserId, ChatWebSocketEvent.messageCreated(roomResponse.roomId(), eventData));

        return ProcessResult.processed(publishTarget);
    }

    private void saveReceipt(
            String moKey, String moNumber, String moCallback, Instant receivedAt,
            Long messageId, SmsInboundProcessingStatus status
    ) {
        receiptRepository.save(SmsInboundReceipt.builder()
                .provider(SmsProvider.UPLUS_MO)
                .externalMessageId(moKey)
                .serviceNumber(moNumber)
                .senderPhone(moCallback)
                .message(messageId == null ? null : messageRepository.getReferenceById(messageId))
                .processingStatus(status)
                .receivedAt(receivedAt)
                .createdAt(Instant.now())
                .build());
    }

    // moRecvDt 공식 포맷(출처: 이번 세션 doc.msghub.uplus.co.kr의 LG U+ 메시지허브 MO API 공식 문서를 두 차례
    // 직접 fetch해 확인 - webhook 예시 payload 전체와 response 예시 양쪽에서 동일하게 "2024-01-29T09:22:55"
    // 형태를 확인했다): timezone offset이 없는 ISO_LOCAL_DATE_TIME이다. Asia/Seoul 기준 현지 시각으로 간주해
    // Instant로 변환한다(공식 문서에 별도 timezone offset 필드/표기가 없으므로 오프셋을 포함하지 않는 형식
    // 그대로 사용한다). null/blank/malformed 같은 예상 밖의 값만 방어적으로 현재 시각으로 대체한다 - 정상
    // 공식 payload가 이 fallback 경로로 빠지는 일은 없어야 한다.
    // package-private(테스트 가시성) - SmsInboundMessageServiceMoRecvDtTest에서 직접 검증한다.
    Instant parseMoRecvDt(String moRecvDt) {
        if (moRecvDt == null || moRecvDt.isBlank()) {
            log.warn("moRecvDt가 없어 현재 시각으로 대체합니다.");
            return Instant.now();
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(moRecvDt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return parsed.atZone(ZoneId.of("Asia/Seoul")).toInstant();
        } catch (DateTimeException exception) {
            log.warn("moRecvDt 형식을 파싱하지 못해 현재 시각으로 대체합니다.");
            return Instant.now();
        }
    }

    record PublishTarget(String recipientUserId, ChatWebSocketEvent event) {
    }

    record ProcessResult(SmsInboundProcessingStatus status, PublishTarget published) {
        static ProcessResult rejected(SmsInboundProcessingStatus status) {
            return new ProcessResult(status, null);
        }

        static ProcessResult processed(PublishTarget published) {
            return new ProcessResult(SmsInboundProcessingStatus.PROCESSED, published);
        }
    }

    public record Outcome(Type type, SmsInboundProcessingStatus status) {
        public enum Type {
            PROCESSED,
            ALREADY_PROCESSED,
            UNSUPPORTED_TYPE
        }

        static Outcome processed(SmsInboundProcessingStatus status) {
            return new Outcome(Type.PROCESSED, status);
        }

        static Outcome alreadyProcessed() {
            return new Outcome(Type.ALREADY_PROCESSED, null);
        }

        static Outcome unsupportedType() {
            return new Outcome(Type.UNSUPPORTED_TYPE, null);
        }
    }
}
