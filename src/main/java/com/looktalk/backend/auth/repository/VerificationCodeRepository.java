package com.looktalk.backend.auth.repository;

import com.looktalk.backend.auth.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {
}
