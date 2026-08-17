package com.looktalk.backend.chat.service;

import com.looktalk.backend.chat.dto.ChatRoomCreateResponse;
import com.looktalk.backend.chat.dto.ChatRoomListItemResponse;
import com.looktalk.backend.chat.dto.ChatRoomTargetResponse;
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
import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.Friendship;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.FriendshipRepository;
import com.looktalk.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53~55. CHAT-002~004
@Service
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final MessageRepository messageRepository;
    private final MessageCiphertextRepository messageCiphertextRepository;
    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final FriendshipRepository friendshipRepository;
    private final ChatDisplayNameResolver chatDisplayNameResolver;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ChatRoomService(
            ChatRoomRepository chatRoomRepository,
            ChatParticipantRepository chatParticipantRepository,
            MessageRepository messageRepository,
            MessageCiphertextRepository messageCiphertextRepository,
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            FriendshipRepository friendshipRepository,
            ChatDisplayNameResolver chatDisplayNameResolver,
            PlatformTransactionManager transactionManager
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.messageRepository = messageRepository;
        this.messageCiphertextRepository = messageCiphertextRepository;
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.friendshipRepository = friendshipRepository;
        this.chatDisplayNameResolver = chatDisplayNameResolver;

        // CHAT-003/004 find-or-create 동시성 처리 전용. 방 생성 시도를 별도의(REQUIRES_NEW) 물리 Transaction으로
        // 격리해, conversation_key UNIQUE 제약 위반으로 실패하더라도 그 실패가 호출 측 Transaction을
        // rollback-only로 오염시키지 않게 한다. 실패 후 재조회는 이 실패한 Transaction과 완전히 분리된
        // 새 Transaction에서 수행한다.
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // CHAT-002
    @Transactional(readOnly = true)
    public List<ChatRoomListItemResponse> getChatRooms(String currentUserId, String typeFilter) {
        ChatRoomType roomTypeFilter = parseRoomType(typeFilter);
        User viewer = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<ChatParticipant> myParticipations =
                chatParticipantRepository.findMyParticipations(currentUserId, roomTypeFilter);
        if (myParticipations.isEmpty()) {
            return List.of();
        }

        List<Long> roomIds = myParticipations.stream()
                .map(p -> p.getRoom().getRoomId())
                .toList();

        Map<Long, List<ChatParticipant>> participantsByRoom = chatParticipantRepository.findAllByRoomIdIn(roomIds)
                .stream()
                .collect(Collectors.groupingBy(p -> p.getRoom().getRoomId()));

        // 같은 room에서 createdAt이 동일한 message가 여러 건 조회될 수 있어(동시 전송 등) messageId가 더 큰
        // 쪽을 최종 "가장 최근 message"로 선택해 room당 정확히 1건으로 확정한다.
        Map<Long, Message> latestMessageByRoom = messageRepository.findLatestPerRoom(roomIds).stream()
                .collect(Collectors.toMap(
                        m -> m.getRoom().getRoomId(),
                        m -> m,
                        (m1, m2) -> m1.getMessageId() > m2.getMessageId() ? m1 : m2
                ));

        List<Long> latestMessageIds = latestMessageByRoom.values().stream().map(Message::getMessageId).toList();
        Map<Long, MessageCiphertext> ciphertextByMessageId = latestMessageIds.isEmpty()
                ? Map.of()
                : messageCiphertextRepository
                        .findAllByMessage_MessageIdInAndRecipientUser_UserId(latestMessageIds, currentUserId)
                        .stream()
                        .collect(Collectors.toMap(c -> c.getMessage().getMessageId(), c -> c));

        // Step 8B - viewer가 STAFF일 때만, 조회된 모든 room의 "상대" 내부 participant 중 PATIENT userId
        // 후보 전체에 대해 alias를 한 번에 배치 조회한다(room 수만큼 반복 조회하지 않기 위함).
        Set<String> candidatePatientUserIds = participantsByRoom.values().stream()
                .flatMap(List::stream)
                .map(ChatParticipant::getUser)
                .filter(Objects::nonNull)
                .filter(user -> user.getRole() == UserRole.PATIENT)
                .map(User::getUserId)
                .collect(Collectors.toSet());
        Map<String, String> aliasByPatientUserId =
                chatDisplayNameResolver.loadAliasesForStaffViewer(viewer, candidatePatientUserIds);

        return myParticipations.stream()
                .map(myParticipation -> toListItemWithSortKey(
                        myParticipation, participantsByRoom, latestMessageByRoom, ciphertextByMessageId, aliasByPatientUserId))
                .sorted(Comparator.comparing(RoomWithSortKey::sortKey, Comparator.reverseOrder()))
                .map(RoomWithSortKey::item)
                .toList();
    }

    private RoomWithSortKey toListItemWithSortKey(
            ChatParticipant myParticipation,
            Map<Long, List<ChatParticipant>> participantsByRoom,
            Map<Long, Message> latestMessageByRoom,
            Map<Long, MessageCiphertext> ciphertextByMessageId,
            Map<String, String> aliasByPatientUserId
    ) {
        ChatRoom room = myParticipation.getRoom();

        ChatParticipant other = participantsByRoom.getOrDefault(room.getRoomId(), List.of()).stream()
                .filter(p -> !p.getParticipantId().equals(myParticipation.getParticipantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "room " + room.getRoomId() + "에 상대 participant가 없습니다."));

        Message latestMessage = latestMessageByRoom.get(room.getRoomId());
        MessageCiphertext ciphertext = latestMessage == null
                ? null
                : ciphertextByMessageId.get(latestMessage.getMessageId());

        // 데이터 무결성 문제로 message는 있으나 현재 사용자용 ciphertext가 없는 경우도, message가 아예 없는
        // 경우와 동일하게 lastMessage 관련 필드를 모두 null로 둔다(docs/LookTalk_Backend_API_Spec_v2.1.md 53).
        EncryptedPayload lastMessageEncryptedPayload = ciphertext == null
                ? null
                : new EncryptedPayload(ciphertext.getEncryptionAlgorithm(), ciphertext.getKeyVersion(), ciphertext.getCiphertext());
        Instant lastMessageAt = ciphertext == null ? null : latestMessage.getCreatedAt();
        Instant sortKey = ciphertext == null ? room.getCreatedAt() : latestMessage.getCreatedAt();

        ChatRoomListItemResponse item = new ChatRoomListItemResponse(
                room.getRoomId(),
                room.getRoomType().name(),
                buildTarget(other, room.getRoomType(), aliasByPatientUserId),
                lastMessageEncryptedPayload,
                lastMessageAt
        );

        return new RoomWithSortKey(item, sortKey);
    }

    private static final String WITHDRAWN_USER_DISPLAY_NAME = "탈퇴한 사용자";

    private ChatRoomTargetResponse buildTarget(
            ChatParticipant other, ChatRoomType roomType, Map<String, String> aliasByPatientUserId) {
        if (roomType == ChatRoomType.SMS) {
            return new ChatRoomTargetResponse("SMS", null, other.getExternalName(), null, other.getExternalPhone());
        }

        User otherUser = other.getUser();
        if (otherUser == null) {
            // 상대가 탈퇴한 내부 participant(V7: user=null, isWithdrawn=true)인 경우.
            // CHAT-005의 기존 탈퇴 사용자 표시 정책과 동일하게 "탈퇴한 사용자"로만 표시하고,
            // 과거 userId/loginId/name은 별도로 복구·snapshot하지 않는다.
            return new ChatRoomTargetResponse("USER", null, null, WITHDRAWN_USER_DISPLAY_NAME, null);
        }
        String displayName = chatDisplayNameResolver.withAliasFallback(
                aliasByPatientUserId.get(otherUser.getUserId()), otherUser);
        return new ChatRoomTargetResponse("USER", otherUser.getUserId(), otherUser.getUserName(), displayName, null);
    }

    private ChatRoomType parseRoomType(String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) {
            return null;
        }
        try {
            return ChatRoomType.valueOf(typeFilter);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
    }

    // CHAT-003 - "target이 없으면 CHAT_TARGET_NOT_FOUND, 자기 자신이면 CHAT_SELF_TARGET" 순서를 명세 그대로 따른다.
    // hospital 소속/staff_patient_assignment/role 조합은 검사하지 않는다.
    public FindOrCreateResult findOrCreateHospitalRoom(String currentUserId, String targetUserId) {
        userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_TARGET_NOT_FOUND));

        if (currentUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.CHAT_SELF_TARGET);
        }

        String conversationKey = buildHospitalConversationKey(currentUserId, targetUserId);
        return findOrCreateRoom(conversationKey,
                () -> createHospitalRoomAndParticipants(conversationKey, currentUserId, targetUserId));
    }

    // CHAT-004
    public FindOrCreateResult findOrCreateSmsRoom(String currentUserId, Long friendshipId) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(currentUser);
        requireSmsVerified(currentUser);
        requireActiveE2eeKey(currentUserId);

        Friendship friendship = friendshipRepository.findByFriendshipIdAndUser_UserId(friendshipId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_NOT_FOUND));

        // Request의 raw phone이 아니라 DB에 저장된(이미 정규화된) friendship.friend_phone을 source of truth로 쓴다.
        String conversationKey = "SMS:" + currentUserId + ":" + friendship.getFriendPhone();
        String friendName = friendship.getFriendName();
        String friendPhone = friendship.getFriendPhone();

        return findOrCreateRoom(conversationKey,
                () -> createSmsRoomAndParticipants(conversationKey, currentUserId, friendName, friendPhone));
    }

    private String buildHospitalConversationKey(String userId1, String userId2) {
        boolean firstIsSmaller = userId1.compareTo(userId2) <= 0;
        String smaller = firstIsSmaller ? userId1 : userId2;
        String larger = firstIsSmaller ? userId2 : userId1;
        return "HOSPITAL:" + smaller + ":" + larger;
    }

    // conversation_key로 기존 room을 먼저 조회하고, 없으면 별도 Transaction에서 생성을 시도한다.
    // 동시 요청으로 conversation_key UNIQUE 제약을 위반하면(DataIntegrityViolationException) 그 실패한
    // Transaction과 분리된 새 Transaction으로 다시 조회해 경쟁에서 이긴 요청이 만든 room을 반환한다.
    private FindOrCreateResult findOrCreateRoom(String conversationKey, Supplier<ChatRoom> creator) {
        Optional<ChatRoom> existing = chatRoomRepository.findByConversationKey(conversationKey);
        if (existing.isPresent()) {
            return new FindOrCreateResult(toCreateResponse(existing.get()), false);
        }

        try {
            ChatRoom created = requiresNewTransactionTemplate.execute(status -> creator.get());
            return new FindOrCreateResult(toCreateResponse(created), true);
        } catch (DataIntegrityViolationException raceLoss) {
            ChatRoom raceWinner = chatRoomRepository.findByConversationKey(conversationKey)
                    .orElseThrow(() -> raceLoss);
            return new FindOrCreateResult(toCreateResponse(raceWinner), false);
        }
    }

    private ChatRoom createHospitalRoomAndParticipants(String conversationKey, String currentUserId, String targetUserId) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_TARGET_NOT_FOUND));

        Instant now = Instant.now();
        ChatRoom room = ChatRoom.builder()
                .roomType(ChatRoomType.HOSPITAL)
                .conversationKey(conversationKey)
                .createdAt(now)
                .build();
        chatRoomRepository.save(room);

        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(currentUser).joinedAt(now).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(targetUser).joinedAt(now).build());

        return room;
    }

    private ChatRoom createSmsRoomAndParticipants(
            String conversationKey, String currentUserId, String friendName, String friendPhone
    ) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Instant now = Instant.now();
        ChatRoom room = ChatRoom.builder()
                .roomType(ChatRoomType.SMS)
                .conversationKey(conversationKey)
                .createdAt(now)
                .build();
        chatRoomRepository.save(room);

        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(currentUser).joinedAt(now).build());
        chatParticipantRepository.save(ChatParticipant.builder()
                .room(room).user(null).externalName(friendName).externalPhone(friendPhone).joinedAt(now).build());

        return room;
    }

    private ChatRoomCreateResponse toCreateResponse(ChatRoom room) {
        return new ChatRoomCreateResponse(room.getRoomId(), room.getRoomType().name(), room.getCreatedAt());
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 16. 전체 Endpoint - CHAT-004는 PATIENT 전용이다.
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

    private void requireActiveE2eeKey(String userId) {
        if (!userE2eeKeyRepository.existsByUser_UserIdAndRevokedAtIsNull(userId)) {
            throw new BusinessException(ErrorCode.E2EE_KEY_REQUIRED);
        }
    }

    private record RoomWithSortKey(ChatRoomListItemResponse item, Instant sortKey) {
    }

    public record FindOrCreateResult(ChatRoomCreateResponse room, boolean created) {
    }
}
