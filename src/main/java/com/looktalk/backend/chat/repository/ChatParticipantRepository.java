package com.looktalk.backend.chat.repository;

import com.looktalk.backend.chat.entity.ChatParticipant;
import com.looktalk.backend.chat.entity.ChatRoomType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    // USER-005 - 회원탈퇴 전에 반드시 먼저 호출한다. is_withdrawn=true와 user=null을 하나의 UPDATE로 함께
    // 반영해야 한다: CHECK 제약(V7)은 즉시(비지연) 평가되므로, 두 컬럼을 별도 statement로 나눠 바꾸면 그 사이에
    // "user_id NOT NULL + is_withdrawn=true"라는 어느 상태에도 속하지 않는 중간 상태가 생겨 위반된다.
    // 외부 SMS participant는 user가 애초에 NULL이라 이 조건에 걸리지 않으므로 함께 갱신되지 않는다.
    // 이 update 이후 users DELETE의 ON DELETE SET NULL은 이미 user_id가 NULL인 행에 대해 no-op이 된다.
    // clearAutomatically/flushAutomatically로 bulk update 전후 영속성 컨텍스트가 stale 상태가 되지 않게 한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChatParticipant p set p.withdrawn = true, p.user = null where p.user.userId = :userId")
    int markInternalParticipantsWithdrawn(@Param("userId") String userId);

    // CHAT-002 - 현재 사용자가 참여 중인 room의 "내 participant" 목록. roomType이 null이면 필터링하지 않는다.
    // room을 함께 fetch해 이후 room 정보 접근 시 N+1이 발생하지 않도록 한다.
    @Query("select p from ChatParticipant p join fetch p.room r "
            + "where p.user.userId = :userId "
            + "and (:roomType is null or r.roomType = :roomType) "
            + "order by r.roomId")
    List<ChatParticipant> findMyParticipations(
            @Param("userId") String userId,
            @Param("roomType") ChatRoomType roomType
    );

    // CHAT-002 - 위에서 찾은 room들의 상대 participant(들)를 한 번에 조회한다(N+1 방지).
    // user는 외부 participant의 경우 NULL이므로 left join fetch를 사용한다.
    @Query("select p from ChatParticipant p join fetch p.room left join fetch p.user "
            + "where p.room.roomId in :roomIds")
    List<ChatParticipant> findAllByRoomIdIn(@Param("roomIds") Collection<Long> roomIds);

    // CHAT-005 - 현재 사용자가 해당 room의 내부 participant인지 확인한다(권한 검사 전용, EXISTS 쿼리).
    boolean existsByRoom_RoomIdAndUser_UserId(Long roomId, String userId);
}
