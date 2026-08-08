package com.looktalk.backend.friend.service;

import com.looktalk.backend.friend.dto.FriendCreateRequest;
import com.looktalk.backend.friend.dto.FriendResponse;
import com.looktalk.backend.friend.dto.FriendUpdateRequest;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.Friendship;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.FriendshipRepository;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.PhoneNormalizer;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 48~51. FRIEND-001~004
// 외부 SMS 지인 CRUD만 처리한다. Chat room/participant/message는 이 단계에서 생성·삭제하지 않는다.
@Service
public class FriendService {

    private static final String STATUS_ACCEPTED = "ACCEPTED";

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;

    public FriendService(FriendshipRepository friendshipRepository, UserRepository userRepository) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
    }

    // FRIEND-001
    @Transactional(readOnly = true)
    public List<FriendResponse> getFriends(String userId) {
        User user = findUser(userId);
        requirePatient(user);

        return friendshipRepository.findAllByUser_UserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // FRIEND-002 - 동일 사용자 행에 락을 걸어 동시 등록으로 중복 전화번호가 생기지 않도록 직렬화한다.
    // (user_id, friend_phone) 부분 UNIQUE 인덱스(V3)는 최종 방어선으로 유지한다.
    @Transactional
    public FriendResponse registerFriend(String userId, FriendCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(user);
        requireSmsVerified(user);

        String normalizedPhone = PhoneNormalizer.normalize(request.phone());
        requireNonBlankNormalizedPhone(normalizedPhone);

        if (friendshipRepository.existsByUser_UserIdAndFriendPhone(userId, normalizedPhone)) {
            throw new BusinessException(ErrorCode.FRIEND_ALREADY_EXISTS);
        }

        Friendship friendship = Friendship.builder()
                .user(user)
                .friendUser(null)
                .friendName(request.name())
                .friendPhone(normalizedPhone)
                .status(STATUS_ACCEPTED)
                .createdAt(Instant.now())
                .build();
        friendshipRepository.save(friendship);

        return toResponse(friendship);
    }

    // FRIEND-003 - 전달된 필드만 수정하는 partial update다. 소유권은 friendshipId + currentUserId 조합으로만
    // 확인해 다른 사용자의 friendship 존재 여부를 노출하지 않는다. 전화번호가 실제로 바뀌는 경우에만 자기
    // 자신을 제외한 중복 검사를 수행한다.
    @Transactional
    public FriendResponse updateFriend(String userId, Long friendshipId, FriendUpdateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(user);
        requireSmsVerified(user);

        Friendship friendship = friendshipRepository.findByFriendshipIdAndUser_UserId(friendshipId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_NOT_FOUND));

        if (request.name() == null && request.phone() == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
            }
            friendship.setFriendName(request.name());
        }

        if (request.phone() != null) {
            String normalizedPhone = PhoneNormalizer.normalize(request.phone());
            requireNonBlankNormalizedPhone(normalizedPhone);

            boolean phoneChanged = !normalizedPhone.equals(friendship.getFriendPhone());
            if (phoneChanged
                    && friendshipRepository.existsByUser_UserIdAndFriendPhoneAndFriendshipIdNot(
                            userId, normalizedPhone, friendshipId)) {
                throw new BusinessException(ErrorCode.FRIEND_ALREADY_EXISTS);
            }
            friendship.setFriendPhone(normalizedPhone);
        }

        return toResponse(friendship);
    }

    // FRIEND-004 - friendship 행만 삭제한다. 기존 SMS chat room/message 이력은 건드리지 않는다.
    @Transactional
    public void deleteFriend(String userId, Long friendshipId) {
        User user = findUser(userId);
        requirePatient(user);

        Friendship friendship = friendshipRepository.findByFriendshipIdAndUser_UserId(friendshipId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FRIEND_NOT_FOUND));

        friendshipRepository.delete(friendship);
    }

    // FRIEND-002/003 - PhoneNormalizer는 정규화(숫자만 남기기)만 담당하므로, 정규화 결과가 숫자를 하나도
    // 포함하지 않아 빈 값이 되는 경우(예: "abcdef", "---")는 여기서 별도로 차단한다.
    // 새로운 ErrorCode를 추가하지 않고 기존 입력 형식 오류 코드(COMMON_VALIDATION_ERROR)를 재사용한다.
    private void requireNonBlankNormalizedPhone(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
    }

    private void requireSmsVerified(User user) {
        if (!Boolean.TRUE.equals(user.getIsSmsVerified())) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_REQUIRED);
        }
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 16. 전체 Endpoint - FRIEND-001~004는 PATIENT 전용이다.
    // Spring Security URL 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private FriendResponse toResponse(Friendship friendship) {
        return new FriendResponse(friendship.getFriendshipId(), friendship.getFriendName(), friendship.getFriendPhone());
    }
}
