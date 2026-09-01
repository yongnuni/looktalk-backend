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

    // U+ MO inbound - moCallback(발신 전화번호)이 해당 환자의 등록된 친구인지 확인하고, 맞다면 SMS room
    // find-or-create에 필요한 friendshipId를 얻는다. 전화번호 하나만으로 전체 사용자를 조회하지 않고 반드시
    // 이미 확정된 patientUserId 스코프 안에서만 조회한다.
    Optional<Friendship> findByUser_UserIdAndFriendPhone(String userId, String friendPhone);
}
