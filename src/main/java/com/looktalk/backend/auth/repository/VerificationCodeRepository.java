package com.looktalk.backend.auth.repository;

import com.looktalk.backend.auth.entity.VerificationCode;
import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.auth.entity.VerificationTargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    // AUTH-002 - "target_type, target_value, purpose가 일치하는 가장 최근 인증 레코드를 조회한다."
    Optional<VerificationCode> findFirstByTargetTypeAndTargetValueAndPurposeOrderByVerificationCodeIdDesc(
            VerificationTargetType targetType,
            String targetValue,
            VerificationPurpose purpose
    );
}
