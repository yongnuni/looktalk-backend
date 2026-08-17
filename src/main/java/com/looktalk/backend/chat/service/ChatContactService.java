package com.looktalk.backend.chat.service;

import com.looktalk.backend.chat.dto.ChatContactListResponse;
import com.looktalk.backend.chat.dto.ChatContactResponse;
import com.looktalk.backend.chat.support.ChatDisplayNameResolver;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 52. CHAT-001
// 병원 소속/담당 환자 관계/role 조합으로 채팅 대상을 제한하지 않는다. PATIENT/STAFF 모두 대상이다.
@Service
public class ChatContactService {

    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final ChatDisplayNameResolver chatDisplayNameResolver;

    public ChatContactService(
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            ChatDisplayNameResolver chatDisplayNameResolver
    ) {
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.chatDisplayNameResolver = chatDisplayNameResolver;
    }

    @Transactional(readOnly = true)
    public ChatContactListResponse getChatContacts(String currentUserId, String keyword, int page, int size) {
        User viewer = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String normalizedKeyword = keyword == null ? "" : keyword.trim();

        Slice<User> slice = userRepository.searchChatContacts(
                currentUserId, normalizedKeyword, PageRequest.of(page, size));

        List<String> userIds = slice.getContent().stream().map(User::getUserId).toList();
        // N+1 방지 - 페이지에 포함된 사용자 수만큼 개별 조회하지 않고, 활성 키를 가진 userId 집합을 한 번에 조회한다.
        Set<String> e2eeReadyUserIds = userIds.isEmpty()
                ? Set.of()
                : Set.copyOf(userE2eeKeyRepository.findUserIdsWithActiveKey(userIds));

        // Step 8B - viewer가 STAFF일 때만 페이지 내 PATIENT 후보에 대해 alias를 한 번에 배치 조회한다(N+1 방지).
        List<String> patientUserIdsInPage = slice.getContent().stream()
                .filter(user -> user.getRole() == UserRole.PATIENT)
                .map(User::getUserId)
                .toList();
        Map<String, String> aliasByPatientUserId =
                chatDisplayNameResolver.loadAliasesForStaffViewer(viewer, patientUserIdsInPage);

        List<ChatContactResponse> content = slice.getContent().stream()
                .map(user -> toResponse(user, e2eeReadyUserIds.contains(user.getUserId()), aliasByPatientUserId))
                .toList();

        return new ChatContactListResponse(content, slice.hasNext());
    }

    private ChatContactResponse toResponse(User user, boolean e2eeReady, Map<String, String> aliasByPatientUserId) {
        String displayName = chatDisplayNameResolver.withAliasFallback(
                aliasByPatientUserId.get(user.getUserId()), user);
        return new ChatContactResponse(
                user.getUserId(),
                user.getRole().name(),
                user.getUserName(),
                displayName,
                e2eeReady
        );
    }
}
