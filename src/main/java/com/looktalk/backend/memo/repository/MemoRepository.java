package com.looktalk.backend.memo.repository;

import com.looktalk.backend.memo.entity.Memo;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoRepository extends JpaRepository<Memo, Long> {

    // MEMO-001 - "최근 수정 순으로 정렬한다."
    List<Memo> findAllByUser_UserIdOrderByUpdatedAtDesc(String userId);

    // MEMO-003/004 - "본인 메모가 아니거나 존재하지 않으면 MEMO_NOT_FOUND를 반환한다."
    // memoId와 currentUserId를 동시에 조건으로 조회해 리소스 존재 여부를 노출하지 않는다.
    Optional<Memo> findByMemoIdAndUser_UserId(Long memoId, String userId);
}
