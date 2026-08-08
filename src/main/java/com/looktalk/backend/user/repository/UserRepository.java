package com.looktalk.backend.user.repository;

import com.looktalk.backend.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByLoginId(String loginId);

    boolean existsByUserEmail(String userEmail);

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByUserEmail(String userEmail);

    Optional<User> findByResetToken(String resetToken);
}
