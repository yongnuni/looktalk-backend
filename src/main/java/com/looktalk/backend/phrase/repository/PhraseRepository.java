package com.looktalk.backend.phrase.repository;

import com.looktalk.backend.phrase.entity.Phrase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhraseRepository extends JpaRepository<Phrase, Long> {

    List<Phrase> findAllByUser_UserIdOrderByCreatedAtDesc(String userId);

    // PHRASE-003/004 - 본인 소유 Phrase인지 memoId 패턴과 동일하게 확인한다.
    Optional<Phrase> findByPhraseIdAndUser_UserId(Long phraseId, String userId);

    long countByUser_UserId(String userId);

    // PHRASE-002 - "created_at ASC 기준 가장 오래된 Phrase를 조회해 삭제한다."
    Optional<Phrase> findFirstByUser_UserIdOrderByCreatedAtAsc(String userId);
}
