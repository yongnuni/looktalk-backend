
package com.looktalk.backend.calibration.repository;

import com.looktalk.backend.calibration.entity.Calibration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRepository extends JpaRepository<Calibration, String> {

    // CAL-001 / CAL-002
    // 사용자의 active calibration 조회
    Optional<Calibration> findByUser_UserIdAndRevokedAtIsNull(String userId);

    // INPUT-001
    // calibrationId와 userId를 함께 확인하여 본인 소유의 Calibration인지 검증
    Optional<Calibration> findByCalibrationIdAndUser_UserId(
            String calibrationId,
            String userId
    );
}
