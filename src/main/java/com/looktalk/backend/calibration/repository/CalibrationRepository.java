package com.looktalk.backend.calibration.repository;

import com.looktalk.backend.calibration.entity.Calibration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRepository extends JpaRepository<Calibration, String> {

    // CAL-001(기존 active 조회 후 revoke) / CAL-002(active 조회) 공통. 사용자당 active(revoked_at IS NULL)는
    // V9 partial unique index로 최대 1개가 보장된다.
    Optional<Calibration> findByUser_UserIdAndRevokedAtIsNull(String userId);
}
