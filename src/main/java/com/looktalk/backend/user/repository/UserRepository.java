package com.looktalk.backend.user.repository;

import com.looktalk.backend.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByLoginId(String loginId);

    boolean existsByUserEmail(String userEmail);

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByUserEmail(String userEmail);

    Optional<User> findByResetToken(String resetToken);

    // CHAT-001 - 현재 사용자를 제외하고 user_name/login_id에 keyword가 포함된 회원을 조회한다.
    // keyword는 Service에서 null/blank를 ""로 정규화해 전달하므로 항상 전체 매치("%%")로 동작한다.
    // 페이지당 size+1건을 조회해 COUNT 쿼리 없이 hasNext를 판단하는 Slice를 사용하고,
    // user_id(고유·불변) 오름차순으로 정렬해 결과 순서를 예측 가능하게 고정한다.
    @Query("select u from User u where u.userId <> :currentUserId "
            + "and (lower(u.userName) like lower(concat('%', :keyword, '%')) "
            + "or lower(u.loginId) like lower(concat('%', :keyword, '%'))) "
            + "order by u.userId asc")
    Slice<User> searchChatContacts(
            @Param("currentUserId") String currentUserId,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    // 사용자 단위 동시성 제어용. 같은 사용자에 대한 동시 요청(예: E2EE 키 등록)을 트랜잭션 동안 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") String userId);
}
