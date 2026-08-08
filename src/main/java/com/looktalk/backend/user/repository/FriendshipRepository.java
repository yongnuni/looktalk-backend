package com.looktalk.backend.user.repository;

import com.looktalk.backend.user.entity.Friendship;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    // FRIEND-001
    List<Friendship> findAllByUser_UserIdOrderByCreatedAtDesc(String userId);

    // FRIEND-003/004 - 소유권 노출 방지를 위해 friendshipId + currentUserId 조합으로만 조회한다.
    Optional<Friendship> findByFriendshipIdAndUser_UserId(Long friendshipId, String userId);

    // FRIEND-002 - 동일 사용자의 동일 전화번호 중복 등록 여부 확인
    boolean existsByUser_UserIdAndFriendPhone(String userId, String friendPhone);

    // FRIEND-003 - 전화번호 변경 시 자기 자신의 현재 friendship은 중복 검사에서 제외한다.
    boolean existsByUser_UserIdAndFriendPhoneAndFriendshipIdNot(String userId, String friendPhone, Long friendshipId);
}
