
package com.looktalk.backend.inputsession.service;

import com.looktalk.backend.calibration.entity.Calibration;
import com.looktalk.backend.calibration.repository.CalibrationRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.inputsession.dto.InputSessionCreateRequest;
import com.looktalk.backend.inputsession.dto.InputSessionResponse;
import com.looktalk.backend.inputsession.entity.InputSession;
import com.looktalk.backend.inputsession.repository.InputSessionRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.entity.UserSetting;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.repository.UserSettingRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InputSessionService {

    private final InputSessionRepository inputSessionRepository;
    private final CalibrationRepository calibrationRepository;
    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final EntityManager entityManager;

    public InputSessionService(
            InputSessionRepository inputSessionRepository,
            CalibrationRepository calibrationRepository,
            UserRepository userRepository,
            UserSettingRepository userSettingRepository,
            EntityManager entityManager
    ) {
        this.inputSessionRepository = inputSessionRepository;
        this.calibrationRepository = calibrationRepository;
        this.userRepository = userRepository;
        this.userSettingRepository = userSettingRepository;
        this.entityManager = entityManager;
    }

    // INPUT-001: 측정 결과 저장
    @Transactional
    public InputSessionResponse createInputSession(
            String userId,
            InputSessionCreateRequest request
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        requirePatient(user);

        String sessionType = request.sessionType();

        Calibration calibration = null;

        if (StringUtils.hasText(request.calibrationId())) {
            calibration = calibrationRepository
                    .findByCalibrationIdAndUser_UserId(
                            request.calibrationId(),
                            userId
                    )
                    .orElseThrow(() ->
                            new BusinessException(ErrorCode.CALIBRATION_NOT_FOUND));
        }

        if ("CALIBRATION_TEST".equals(sessionType) && calibration == null) {
            throw new BusinessException(ErrorCode.CALIBRATION_NOT_FOUND);
        }

        String keyboardLayout = resolveKeyboardLayout(
                userId,
                request.keyboardLayout()
        );

        InputSession inputSession = InputSession.builder()
                .logId(UUID.randomUUID().toString())
                .user(user)
                .calibration(calibration)
                .inputMethod(request.inputMethod())
                .sessionType(sessionType)
                .keyboardLayout(keyboardLayout)
                .typoRate(request.typoRate())
                .inputSpeed(request.inputSpeed())
                .recognitionAccuracy(request.recognitionAccuracy())
                .inputStability(request.inputStability())
                .build();

        // 저장 후 영속성 컨텍스트에서 관리되는 객체를 사용한다.
        InputSession savedInputSession =
                inputSessionRepository.saveAndFlush(inputSession);

        // session_at은 DB의 CURRENT_TIMESTAMP 기본값을 사용한다.
        entityManager.refresh(savedInputSession);

        return toResponse(savedInputSession);
    }

    // INPUT-002: 본인의 측정 이력 조회
    @Transactional(readOnly = true)
    public List<InputSessionResponse> getInputSessions(
            String userId,
            String sessionType,
            int size
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        requirePatient(user);

        if (!"CALIBRATION_TEST".equals(sessionType)
                && !"NORMAL_USE".equals(sessionType)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }

        int pageSize = Math.max(1, Math.min(size, 100));

        return inputSessionRepository
                .findByUser_UserIdAndSessionTypeOrderBySessionAtDesc(
                        userId,
                        sessionType,
                        PageRequest.of(0, pageSize)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private String resolveKeyboardLayout(
            String userId,
            String requestedLayout
    ) {
        if (StringUtils.hasText(requestedLayout)) {
            return requestedLayout;
        }

        return userSettingRepository.findById(userId)
                .map(UserSetting::getKeyboardLayout)
                .filter(StringUtils::hasText)
                .orElse("QWERTY");
    }

    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private InputSessionResponse toResponse(InputSession inputSession) {
        return new InputSessionResponse(
                inputSession.getLogId(),
                inputSession.getCalibration() == null
                        ? null
                        : inputSession.getCalibration().getCalibrationId(),
                inputSession.getInputMethod(),
                inputSession.getSessionType(),
                inputSession.getKeyboardLayout(),
                inputSession.getTypoRate(),
                inputSession.getInputSpeed(),
                inputSession.getRecognitionAccuracy(),
                inputSession.getInputStability(),
                inputSession.getSessionAt()
        );
    }
}
