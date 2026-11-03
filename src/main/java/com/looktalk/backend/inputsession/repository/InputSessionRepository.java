
package com.looktalk.backend.inputsession.repository;

import com.looktalk.backend.inputsession.entity.InputSession;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InputSessionRepository extends JpaRepository<InputSession, String> {

    List<InputSession> findByUser_UserIdAndSessionTypeOrderBySessionAtDesc(
            String userId,
            String sessionType,
            Pageable pageable
    );
}
