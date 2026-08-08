package com.looktalk.backend.auth.refreshtoken.repository;

import com.looktalk.backend.auth.refreshtoken.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :revokedAt "
            + "where r.user.userId = :userId and r.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") String userId, @Param("revokedAt") Instant revokedAt);
}
