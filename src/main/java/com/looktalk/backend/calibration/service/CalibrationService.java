package com.looktalk.backend.calibration.service;

import com.looktalk.backend.calibration.dto.CalibrationCreateRequest;
import com.looktalk.backend.calibration.dto.CalibrationResponse;
import com.looktalk.backend.calibration.entity.Calibration;
import com.looktalk.backend.calibration.repository.CalibrationRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 11. CAL-001/CAL-002
@Service
public class CalibrationService {

    private final CalibrationRepository calibrationRepository;
    private final UserRepository userRepository;
    private final CalibrationDataValidator calibrationDataValidator;

    public CalibrationService(
            CalibrationRepository calibrationRepository,
            UserRepository userRepository,
            CalibrationDataValidator calibrationDataValidator
    ) {
        this.calibrationRepository = calibrationRepository;
        this.userRepository = userRepository;
        this.calibrationDataValidator = calibrationDataValidator;
    }

    // CAL-001 - 사용자 row를 pessimistic lock한 뒤(UserRepository.findByIdForUpdate, 기존 E2EE 키 등록과 동일
    // 패턴) 같은 트랜잭션에서 "기존 active revoke -> 신규 active insert"를 수행한다. 동시에 두 POST가 들어와도
    // 락으로 직렬화되어 최종 active는 정확히 1개가 된다. V9 partial unique index(ux_calibration_active_user)는
    // 최종 안전망이다. validation/insert 중 하나라도 실패하면 전체 트랜잭션이 rollback되어 기존 active는
    // 그대로 유지된다.
    @Transactional
    public CalibrationResponse createCalibration(String userId, CalibrationCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(user);

        calibrationDataValidator.validate(request.calibrationData());

        calibrationRepository.findByUser_UserIdAndRevokedAtIsNull(userId)
                .ifPresent(existing -> existing.setRevokedAt(Instant.now()));
        // Hibernate의 기본 flush 순서는 코드 실행 순서와 무관하게 action queue 기준(INSERT가 UPDATE보다 먼저)으로
        // 처리된다. revoke(UPDATE)를 먼저 DB에 반영하지 않으면 아래 신규 INSERT 시점에 두 row가 동시에
        // active(revoked_at IS NULL)로 보여 ux_calibration_active_user partial unique index를 위반한다.
        calibrationRepository.flush();

        Calibration calibration = Calibration.builder()
                .calibrationId(UUID.randomUUID().toString())
                .user(user)
                .algorithmVersion(request.algorithmVersion())
                .calibrationData(request.calibrationData())
                .createdAt(Instant.now())
                .build();
        calibrationRepository.save(calibration);

        return toResponse(calibration);
    }

    // CAL-002
    @Transactional(readOnly = true)
    public CalibrationResponse getActiveCalibration(String userId) {
        User user = findUser(userId);
        requirePatient(user);

        Calibration active = calibrationRepository.findByUser_UserIdAndRevokedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CALIBRATION_NOT_FOUND));

        return toResponse(active);
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 19. Security 권한 - Spring Security URL
    // 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private CalibrationResponse toResponse(Calibration calibration) {
        return new CalibrationResponse(
                calibration.getCalibrationId(),
                calibration.getAlgorithmVersion(),
                calibration.getCalibrationData(),
                calibration.getCreatedAt()
        );
    }
}
