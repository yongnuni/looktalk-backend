package com.looktalk.backend.chat.service;

import com.looktalk.backend.chat.dto.ChatMessageCiphertextRequest;
import com.looktalk.backend.chat.dto.ChatMessageCreatedEventData;
import com.looktalk.backend.chat.dto.ChatMessageListResponse;
import com.looktalk.backend.chat.dto.ChatMessageResponse;
import com.looktalk.backend.chat.dto.ChatMessageSendResponse;
import com.looktalk.backend.chat.dto.ChatSendMessageRequest;
import com.looktalk.backend.chat.dto.ChatWebSocketEvent;
import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.ChatRoom;
import com.looktalk.backend.chat.entity.ChatRoomType;
import com.looktalk.backend.chat.entity.Message;
import com.looktalk.backend.chat.entity.MessageCiphertext;
import com.looktalk.backend.chat.repository.ChatParticipantRepository;
import com.looktalk.backend.chat.repository.ChatRoomRepository;
import com.looktalk.backend.chat.repository.MessageCiphertextRepository;
import com.looktalk.backend.chat.repository.MessageRepository;
import com.looktalk.backend.chat.support.ChatDisplayNameResolver;
import com.looktalk.backend.chat.websocket.ChatRealtimePublisher;
import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.e2ee.service.EncryptedPayloadValidator;
import com.looktalk.backend.e2ee.service.SealedBoxEncryptor;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.DisplayNameResolver;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// docs/LookTalk_Backend_API_Spec_v2.1.md 56~57. CHAT-005/006
@Service
public class ChatMessageService {

    private static final String WITHDRAWN_USER_DISPLAY_NAME = "탈퇴한 사용자";
    private static final int DEFAULT_SIZE = 50;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SUPPORTED_MESSAGE_TYPE = "TEXT";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final MessageRepository messageRepository;
    private final MessageCiphertextRepository messageCiphertextRepository;
    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final EncryptedPayloadValidator payloadValidator;
    private final SealedBoxEncryptor sealedBoxEncryptor;
    private final SmsMessageSender smsMessageSender;
    private final ChatRealtimePublisher realtimePublisher;
    private final ChatDisplayNameResolver chatDisplayNameResolver;
    private final TransactionTemplate transactionTemplate;

    public ChatMessageService(
            ChatRoomRepository chatRoomRepository,
            ChatParticipantRepository chatParticipantRepository,
            MessageRepository messageRepository,
            MessageCiphertextRepository messageCiphertextRepository,
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            EncryptedPayloadValidator payloadValidator,
            SealedBoxEncryptor sealedBoxEncryptor,
            SmsMessageSender smsMessageSender,
            ChatRealtimePublisher realtimePublisher,
            ChatDisplayNameResolver chatDisplayNameResolver,
            PlatformTransactionManager transactionManager
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.messageRepository = messageRepository;
        this.messageCiphertextRepository = messageCiphertextRepository;
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.payloadValidator = payloadValidator;
        this.sealedBoxEncryptor = sealedBoxEncryptor;
        this.smsMessageSender = smsMessageSender;
        this.realtimePublisher = realtimePublisher;
        this.chatDisplayNameResolver = chatDisplayNameResolver;

        // CHAT-006 SMS는 Provider network 호출을 DB write Transaction 밖에서 수행해야 한다. sendMessage() 자체를
        // @Transactional로 두면 private 메서드 호출에도 걸려 Provider 호출까지 트랜잭션에 포함되므로,
        // 저장 단계만 명시적으로 감싸는 TransactionTemplate을 사용한다(HOSPITAL 저장에도 동일하게 사용해 일관성을 둔다).
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // CHAT-005
    @Transactional(readOnly = true)
    public ChatMessageListResponse getMessages(String currentUserId, Long roomId, Long beforeMessageId, Integer size) {
        int pageSize = resolveSize(size);
        requireValidCursor(beforeMessageId);

        chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // room 존재 여부와 별개로, participant가 아니면 CHAT_ROOM_NOT_FOUND로 숨기지 않고 CHAT_ACCESS_DENIED를
        // 반환한다. hospital 소속/staff_patient_assignment는 검사하지 않는다.
        if (!chatParticipantRepository.existsByRoom_RoomIdAndUser_UserId(roomId, currentUserId)) {
            throw new BusinessException(ErrorCode.CHAT_ACCESS_DENIED);
        }
        User viewer = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 1단계 - 현재 사용자용 ciphertext가 존재하는 message만을 대상으로 messageId DESC cursor pagination.
        // size+1건을 가져와 COUNT 쿼리 없이 hasNext를 판단한다.
        List<Long> fetchedIds = messageRepository.findMessageIdsForCurrentUser(
                roomId, currentUserId, beforeMessageId, PageRequest.of(0, pageSize + 1));

        boolean hasNext = fetchedIds.size() > pageSize;
        List<Long> pageIds = hasNext ? fetchedIds.subList(0, pageSize) : fetchedIds;
        Long nextCursor = hasNext ? pageIds.get(pageIds.size() - 1) : null;

        if (pageIds.isEmpty()) {
            return new ChatMessageListResponse(List.of(), false, null);
        }

        // 2단계 - 확정된 messageId들에 대해 message+sender와 ciphertext를 각각 한 번의 batch 쿼리로 조회한다(N+1 방지).
        Map<Long, Message> messageById = messageRepository.findAllByIdInWithSender(pageIds).stream()
                .collect(Collectors.toMap(Message::getMessageId, m -> m));
        Map<Long, MessageCiphertext> ciphertextByMessageId = messageCiphertextRepository
                .findAllByMessage_MessageIdInAndRecipientUser_UserId(pageIds, currentUserId).stream()
                .collect(Collectors.toMap(c -> c.getMessage().getMessageId(), c -> c));

        // Step 8B - viewer가 STAFF일 때만, 이 페이지에 등장하는 sender 중 PATIENT userId 후보 전체에 대해
        // alias를 한 번에 배치 조회한다(message 수만큼 반복 조회하지 않기 위함).
        Set<String> candidateSenderPatientUserIds = messageById.values().stream()
                .map(m -> m.getSenderParticipant().getUser())
                .filter(user -> user != null && user.getRole() == UserRole.PATIENT)
                .map(User::getUserId)
                .collect(Collectors.toSet());
        Map<String, String> aliasByPatientUserId =
                chatDisplayNameResolver.loadAliasesForStaffViewer(viewer, candidateSenderPatientUserIds);

        // 1단계가 정한 messageId DESC 순서를 그대로 유지해 최종 Response를 만든다(IN 절 결과는 순서 미보장).
        List<ChatMessageResponse> messages = pageIds.stream()
                .map(id -> toResponse(messageById.get(id), ciphertextByMessageId.get(id), aliasByPatientUserId))
                .toList();

        return new ChatMessageListResponse(messages, hasNext, nextCursor);
    }

    private ChatMessageResponse toResponse(Message message, MessageCiphertext ciphertext, Map<String, String> aliasByPatientUserId) {
        if (message == null || ciphertext == null) {
            // 1단계 EXISTS 조건이 이미 "현재 사용자용 ciphertext가 있는 message"만 골라냈으므로 도달하지 않아야
            // 한다. 다른 recipient의 ciphertext로 대체하거나 평문 fallback을 만드는 대신 즉시 예외로 드러낸다.
            throw new IllegalStateException("message " + (message == null ? null : message.getMessageId())
                    + "의 sender 또는 ciphertext 조회 결과가 1단계와 일치하지 않습니다.");
        }

        ChatParticipant sender = message.getSenderParticipant();
        String senderUserId;
        String senderDisplayName;

        User senderUser = sender.getUser();
        if (senderUser != null) {
            senderUserId = senderUser.getUserId();
            senderDisplayName = chatDisplayNameResolver.withAliasFallback(
                    aliasByPatientUserId.get(senderUser.getUserId()), senderUser);
        } else if (sender.isWithdrawn()) {
            senderUserId = null;
            senderDisplayName = WITHDRAWN_USER_DISPLAY_NAME;
        } else {
            // 외부 SMS participant - friendship을 다시 조회하지 않고 room 생성 당시 snapshot을 그대로 쓴다.
            senderUserId = null;
            senderDisplayName = sender.getExternalName();
        }

        EncryptedPayload encryptedPayload = new EncryptedPayload(
                ciphertext.getEncryptionAlgorithm(), ciphertext.getKeyVersion(), ciphertext.getCiphertext());

        return new ChatMessageResponse(
                message.getMessageId(),
                sender.getParticipantId(),
                senderUserId,
                senderDisplayName,
                encryptedPayload,
                message.getMessageType(),
                message.getCreatedAt()
        );
    }

    private int resolveSize(Integer size) {
        int resolved = size == null ? DEFAULT_SIZE : size;
        if (resolved < MIN_SIZE || resolved > MAX_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
        return resolved;
    }

    private void requireValidCursor(Long beforeMessageId) {
        if (beforeMessageId != null && beforeMessageId <= 0) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
    }

    // CHAT-006
    public ChatMessageSendResponse sendMessage(String currentUserId, Long roomId, ChatSendMessageRequest request) {
        requireSupportedMessageType(request.messageType());

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> participants = chatParticipantRepository.findAllByRoomIdIn(List.of(roomId));
        ChatParticipant myParticipant = participants.stream()
                .filter(p -> p.getUser() != null && currentUserId.equals(p.getUser().getUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ACCESS_DENIED));

        if (room.getRoomType() == ChatRoomType.HOSPITAL) {
            return sendHospitalMessage(currentUserId, room, myParticipant, participants, request);
        }
        return sendSmsMessage(currentUserId, room, myParticipant, participants, request);
    }

    private void requireSupportedMessageType(String messageType) {
        if (!SUPPORTED_MESSAGE_TYPE.equals(messageType)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
    }

    // 57.1 HOSPITAL - room의 활성(비탈퇴) 내부 participant 전원의 userId 집합과 Request recipient 집합이
    // 정확히 일치해야 하며, 각 ciphertext는 recipient의 "현재 active" keyVersion으로만 암호화되어 있어야 한다.
    private ChatMessageSendResponse sendHospitalMessage(
            String currentUserId,
            ChatRoom room,
            ChatParticipant myParticipant,
            List<ChatParticipant> participants,
            ChatSendMessageRequest request
    ) {
        if (request.content() != null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
        if (request.ciphertexts() == null || request.ciphertexts().isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }

        List<ChatParticipant> activeInternal = participants.stream()
                .filter(p -> p.getUser() != null)
                .toList();
        boolean anyWithdrawn = participants.stream().anyMatch(ChatParticipant::isWithdrawn);

        List<String> activeUserIds = activeInternal.stream().map(p -> p.getUser().getUserId()).toList();
        Map<String, User> userByUserId = activeInternal.stream()
                .collect(Collectors.toMap(p -> p.getUser().getUserId(), ChatParticipant::getUser));
        Map<String, UserE2eeKey> activeKeyByUserId = userE2eeKeyRepository.findActiveKeysByUserIds(activeUserIds)
                .stream()
                .collect(Collectors.toMap(k -> k.getUser().getUserId(), k -> k));

        if (!activeKeyByUserId.containsKey(currentUserId)) {
            throw new BusinessException(ErrorCode.E2EE_KEY_REQUIRED);
        }
        if (anyWithdrawn) {
            // 탈퇴 전 이력은 CHAT-005로 계속 조회 가능하지만, 더 이상 유효한 내부 상대가 없으므로 새로 전송할 수 없다.
            throw new BusinessException(ErrorCode.CHAT_TARGET_NOT_FOUND);
        }
        for (String userId : activeUserIds) {
            if (!activeKeyByUserId.containsKey(userId)) {
                throw new BusinessException(ErrorCode.E2EE_TARGET_KEY_REQUIRED);
            }
        }

        List<ChatMessageCiphertextRequest> ciphertexts = request.ciphertexts();
        List<String> requestRecipientIds = ciphertexts.stream()
                .map(ChatMessageCiphertextRequest::recipientUserId)
                .toList();
        Set<String> requestRecipientIdSet = new HashSet<>(requestRecipientIds);

        // Set으로 조용히 합쳐지는 대신 중복을 명시적으로 탐지해 거부한다.
        if (requestRecipientIdSet.size() != requestRecipientIds.size()) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }
        if (!requestRecipientIdSet.equals(new HashSet<>(activeUserIds))) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }

        for (ChatMessageCiphertextRequest item : ciphertexts) {
            payloadValidator.validate(new EncryptedPayload(item.algorithm(), item.keyVersion(), item.ciphertext()));

            UserE2eeKey activeKey = activeKeyByUserId.get(item.recipientUserId());
            if (!activeKey.getKeyVersion().equals(item.keyVersion())) {
                throw new BusinessException(ErrorCode.E2EE_KEY_NOT_FOUND);
            }
        }

        Message saved = transactionTemplate.execute(status -> {
            Message message = Message.builder()
                    .room(room)
                    .senderParticipant(myParticipant)
                    .messageType(request.messageType())
                    .createdAt(Instant.now())
                    .build();
            messageRepository.save(message);

            for (ChatMessageCiphertextRequest item : ciphertexts) {
                messageCiphertextRepository.save(MessageCiphertext.builder()
                        .message(message)
                        .recipientUser(userByUserId.get(item.recipientUserId()))
                        .keyVersion(item.keyVersion())
                        .encryptionAlgorithm(item.algorithm())
                        .ciphertext(item.ciphertext())
                        .build());
            }
            return message;
        });

        // transactionTemplate.execute()가 정상 반환한 시점은 이미 commit이 완료된 이후다. WebSocket publish는
        // 반드시 이 뒤에서만 수행한다(저장 callback 내부에서 publish하지 않는다 - 58. commit 이후에만 발행).
        // HOSPITAL은 활성 내부 participant 각각에게 그 사용자용 ciphertext만 담은 별도 Event를 보낸다
        // (sender 본인도 포함).
        //
        // Step 8B - senderDisplayName은 recipient마다 다를 수 있다(STAFF recipient는 자신의 patientAlias를,
        // PATIENT recipient/자기 자신은 항상 전역 userName -> loginId를 본다). 루프 밖에서 한 번만 계산해
        // 모든 recipient에게 재사용하면 안 되므로, sender가 PATIENT인 경우에 한해 STAFF recipient들의 alias를
        // 배치 조회(recipient 수만큼 반복 쿼리하지 않기 위함)한 뒤 recipient별로 개별 계산한다.
        User sender = userByUserId.get(currentUserId);
        List<String> staffRecipientUserIds = activeUserIds.stream()
                .filter(userId -> userByUserId.get(userId).getRole() == UserRole.STAFF)
                .toList();
        Map<String, String> aliasByStaffUserId =
                chatDisplayNameResolver.loadAliasesForPatientSender(sender, staffRecipientUserIds);

        for (ChatMessageCiphertextRequest item : ciphertexts) {
            String recipientDisplayName = chatDisplayNameResolver.withAliasFallback(
                    aliasByStaffUserId.get(item.recipientUserId()), sender);
            ChatMessageCreatedEventData eventData = new ChatMessageCreatedEventData(
                    saved.getMessageId(),
                    myParticipant.getParticipantId(),
                    currentUserId,
                    recipientDisplayName,
                    new EncryptedPayload(item.algorithm(), item.keyVersion(), item.ciphertext()),
                    saved.getCreatedAt()
            );
            realtimePublisher.publishMessageCreated(
                    item.recipientUserId(), ChatWebSocketEvent.messageCreated(room.getRoomId(), eventData));
        }

        return new ChatMessageSendResponse(saved.getMessageId(), saved.getCreatedAt());
    }

    // 57.2 SMS - Provider network 호출은 DB write Transaction 밖에서 수행한다. Provider가 발송을 수락한
    // 경우에만 현재 사용자의 "현재 active" 공개키로 content를 Backend가 sealed box 암호화해 저장한다.
    private ChatMessageSendResponse sendSmsMessage(
            String currentUserId,
            ChatRoom room,
            ChatParticipant myParticipant,
            List<ChatParticipant> participants,
            ChatSendMessageRequest request
    ) {
        if (request.ciphertexts() != null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(currentUser);
        requireSmsVerified(currentUser);

        UserE2eeKey activeKey = userE2eeKeyRepository
                .findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.E2EE_KEY_REQUIRED));

        // room의 외부 SMS participant. friendship을 다시 조회하지 않고 CHAT-004 생성 당시 snapshot을 사용한다.
        ChatParticipant externalParticipant = participants.stream()
                .filter(p -> p.getUser() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "room " + room.getRoomId() + "은 SMS room인데 외부 participant가 없습니다."));
        String phone = externalParticipant.getExternalPhone();

        if (!smsMessageSender.isConfigured()) {
            throw new BusinessException(ErrorCode.SMS_PROVIDER_NOT_CONFIGURED);
        }
        try {
            smsMessageSender.send(phone, request.content());
        } catch (SmsSendFailedException exception) {
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED);
        }

        // Provider 수락 이후에만 평문을 Backend가 암호화한다. 이 시점 이전에는 어떤 write transaction도 열려 있지
        // 않으므로 Provider 호출이 DB write transaction을 붙잡지 않는다.
        String ciphertext = sealedBoxEncryptor.encrypt(request.content(), activeKey.getPublicKey());

        Message saved = transactionTemplate.execute(status -> {
            Message message = Message.builder()
                    .room(room)
                    .senderParticipant(myParticipant)
                    .messageType(request.messageType())
                    .createdAt(Instant.now())
                    .build();
            messageRepository.save(message);

            messageCiphertextRepository.save(MessageCiphertext.builder()
                    .message(message)
                    .recipientUser(currentUser)
                    .keyVersion(activeKey.getKeyVersion())
                    .encryptionAlgorithm(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX)
                    .ciphertext(ciphertext)
                    .build());

            return message;
        });

        // commit 완료 이후에만 publish한다(58). SMS recipient는 현재 PATIENT 한 명뿐이다 - 외부 SMS 상대는
        // Look Talk 사용자가 아니므로 WebSocket 수신자가 아니다.
        ChatMessageCreatedEventData eventData = new ChatMessageCreatedEventData(
                saved.getMessageId(),
                myParticipant.getParticipantId(),
                currentUserId,
                DisplayNameResolver.resolve(currentUser),
                new EncryptedPayload(EncryptedPayloadValidator.LIBSODIUM_SEALED_BOX, activeKey.getKeyVersion(), ciphertext),
                saved.getCreatedAt()
        );
        realtimePublisher.publishMessageCreated(
                currentUserId, ChatWebSocketEvent.messageCreated(room.getRoomId(), eventData));

        return new ChatMessageSendResponse(saved.getMessageId(), saved.getCreatedAt());
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 55, 57.2 - SMS는 PATIENT 전용이다.
    // Spring Security URL 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private void requireSmsVerified(User user) {
        if (!Boolean.TRUE.equals(user.getIsSmsVerified())) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_REQUIRED);
        }
    }
}
