package com.looktalk.backend.sms.service;

import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsInboundNumberAssignmentRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.PhoneNormalizer;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// "환자 <-> U+ MO 수신 서비스 번호" 매핑 관리. 번호 자체를 U+에서 구매/발급하는 것은 Backend의 역할이 아니다
// - 이미 외부에서 확보한 번호를 환자에게 배정/교체/해제하는 것만 관리한다.
// 일반 PATIENT는 스스로 번호를 등록할 수 없다 - 이 서비스는 STAFF 전용 Controller(SmsInboundNumberAssignmentController)
// 에서만 호출된다.
@Service
public class SmsInboundNumberAssignmentService {

    private final SmsInboundNumberAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public SmsInboundNumberAssignmentService(
            SmsInboundNumberAssignmentRepository assignmentRepository,
            UserRepository userRepository
    ) {
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<SmsInboundNumberAssignment> findActiveByUserId(String userId) {
        return assignmentRepository.findByUser_UserIdAndActiveTrue(userId);
    }

    @Transactional(readOnly = true)
    public Optional<SmsInboundNumberAssignment> findActiveByServiceNumber(String serviceNumber) {
        return assignmentRepository.findByServiceNumberAndActiveTrue(serviceNumber);
    }

    // 환자에게 서비스 번호를 배정(또는 교체)한다. 기존 active 배정이 있으면 비활성화하고 history로 남긴다.
    // 동일 번호가 다른 환자에게 이미 active 상태로 배정되어 있으면 SMS_INBOUND_NUMBER_ALREADY_ASSIGNED를
    // 던진다 - ux_sms_inbound_assignment_active_service_number(V10) UNIQUE 인덱스가 최종 방어선이다.
    @Transactional
    public SmsInboundNumberAssignment assign(String patientUserId, String rawServiceNumber, SmsProvider provider) {
        User patient = userRepository.findById(patientUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(patient);

        String normalizedNumber = PhoneNormalizer.normalize(rawServiceNumber);

        assignmentRepository.findByServiceNumberAndActiveTrue(normalizedNumber)
                .filter(existing -> !existing.getUser().getUserId().equals(patientUserId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.SMS_INBOUND_NUMBER_ALREADY_ASSIGNED);
                });

        Instant now = Instant.now();
        assignmentRepository.findByUser_UserIdAndActiveTrue(patientUserId)
                .ifPresent(existing -> {
                    existing.setActive(false);
                    existing.setDeactivatedAt(now);
                    existing.setUpdatedAt(now);
                });

        SmsInboundNumberAssignment assignment = SmsInboundNumberAssignment.builder()
                .user(patient)
                .provider(provider)
                .serviceNumber(normalizedNumber)
                .active(true)
                .activatedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return assignmentRepository.save(assignment);
    }

    // 환자의 현재 active 배정을 해제한다(history는 유지, 행을 삭제하지 않는다).
    @Transactional
    public void deactivate(String patientUserId) {
        SmsInboundNumberAssignment assignment = assignmentRepository.findByUser_UserIdAndActiveTrue(patientUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SMS_INBOUND_NUMBER_NOT_ASSIGNED));

        Instant now = Instant.now();
        assignment.setActive(false);
        assignment.setDeactivatedAt(now);
        assignment.setUpdatedAt(now);
    }

    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}
