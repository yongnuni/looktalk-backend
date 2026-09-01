package com.looktalk.backend.chat.repository;

import com.looktalk.backend.chat.entity.Message;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // V10 - outbound SMS(CHAT-006) client-generated idempotency key 재사용 검사. 동일 room+sender+key로
    // 재요청이 오면 Provider를 다시 호출하지 않고 기존 message를 그대로 반환한다.
    Optional<Message> findByRoom_RoomIdAndSenderParticipant_ParticipantIdAndClientMessageId(
            Long roomId, Long senderParticipantId, String clientMessageId);

    // CHAT-002 - 여러 room의 "가장 최근 message"를 한 번의 쿼리로 조회한다(방마다 별도 쿼리를 날리는 N+1 방지).
    // 같은 room에서 동일 created_at을 가진 message가 여러 건 나올 수 있어(동시 전송 등) 결과가 room당 1건임을
    // 보장하지 않는다 - 호출부(Service)에서 room별 message_id 최댓값으로 최종 1건을 선택해 동점을 해소한다.
    @Query("select m from Message m where m.room.roomId in :roomIds "
            + "and m.createdAt = (select max(m2.createdAt) from Message m2 where m2.room.roomId = m.room.roomId)")
    List<Message> findLatestPerRoom(@Param("roomIds") Collection<Long> roomIds);

    // CHAT-005 1단계 - "현재 사용자용 ciphertext가 존재하는" message만을 대상으로 messageId DESC cursor
    // pagination을 수행한다(EXISTS로 조인 없이 필터링). ciphertext가 없는 message는 애초에 후보에서 제외되므로
    // size/hasNext/nextCursor가 나중에 Java에서 걸러내며 어긋나는 문제가 생기지 않는다.
    // Pageable(size+1)만 적용해 LIMIT만 걸고 COUNT 쿼리는 발생시키지 않는다(List 반환 + Pageable 조합).
    @Query("select m.messageId from Message m "
            + "where m.room.roomId = :roomId "
            + "and (:beforeMessageId is null or m.messageId < :beforeMessageId) "
            + "and exists ("
            + "  select 1 from MessageCiphertext c "
            + "  where c.message = m and c.recipientUser.userId = :currentUserId"
            + ") "
            + "order by m.messageId desc")
    List<Long> findMessageIdsForCurrentUser(
            @Param("roomId") Long roomId,
            @Param("currentUserId") String currentUserId,
            @Param("beforeMessageId") Long beforeMessageId,
            Pageable pageable
    );

    // CHAT-005 2단계 - 1단계에서 확정된 messageId 목록에 대해 message + sender participant + sender user를
    // 한 번에 배치 조회한다(N+1 방지). sender_participant_id는 NOT NULL이라 inner join fetch를 쓰고,
    // sender의 user는 외부/탈퇴 participant에서 NULL일 수 있어 left join fetch를 쓴다.
    // IN 절 결과는 순서를 보장하지 않으므로 호출부(Service)에서 1단계가 정한 순서로 다시 정렬한다.
    @Query("select m from Message m "
            + "join fetch m.senderParticipant sp "
            + "left join fetch sp.user "
            + "where m.messageId in :messageIds")
    List<Message> findAllByIdInWithSender(@Param("messageIds") Collection<Long> messageIds);
}
