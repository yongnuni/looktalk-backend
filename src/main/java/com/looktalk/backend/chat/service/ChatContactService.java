package com.looktalk.backend.chat.service;

import com.looktalk.backend.chat.dto.ChatContactListResponse;
import com.looktalk.backend.chat.dto.ChatContactResponse;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.DisplayNameResolver;
import java.util.List;
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

    public ChatContactService(UserRepository userRepository, UserE2eeKeyRepository userE2eeKeyRepository) {
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
    }

    @Transactional(readOnly = true)
    public ChatContactListResponse getChatContacts(String currentUserId, String keyword, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();

        Slice<User> slice = userRepository.searchChatContacts(
                currentUserId, normalizedKeyword, PageRequest.of(page, size));

        List<String> userIds = slice.getContent().stream().map(User::getUserId).toList();
        // N+1 방지 - 페이지에 포함된 사용자 수만큼 개별 조회하지 않고, 활성 키를 가진 userId 집합을 한 번에 조회한다.
        Set<String> e2eeReadyUserIds = userIds.isEmpty()
                ? Set.of()
                : Set.copyOf(userE2eeKeyRepository.findUserIdsWithActiveKey(userIds));

        List<ChatContactResponse> content = slice.getContent().stream()
                .map(user -> toResponse(user, e2eeReadyUserIds.contains(user.getUserId())))
                .toList();

        return new ChatContactListResponse(content, slice.hasNext());
    }

    private ChatContactResponse toResponse(User user, boolean e2eeReady) {
        return new ChatContactResponse(
                user.getUserId(),
                user.getRole().name(),
                user.getUserName(),
                DisplayNameResolver.resolve(user),
                e2eeReady
        );
    }
}
