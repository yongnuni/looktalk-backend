package com.looktalk.backend.emergency.service;

import com.looktalk.backend.emergency.dto.EmergencyCallCreateResponse;
import com.looktalk.backend.emergency.dto.EmergencyCallCreatedEventData;
import com.looktalk.backend.emergency.dto.EmergencyCallListItemResponse;
import com.looktalk.backend.emergency.dto.EmergencyCallPageResponse;
import com.looktalk.backend.emergency.dto.EmergencyCallReadResponse;
import com.looktalk.backend.emergency.dto.EmergencyWebSocketEvent;
import com.looktalk.backend.emergency.entity.EmergencyCall;
import com.looktalk.backend.emergency.repository.EmergencyCallRepository;
import com.looktalk.backend.emergency.websocket.EmergencyRealtimePublisher;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.staff.entity.Staff;
import com.looktalk.backend.staff.entity.StaffPatientAssignment;
import com.looktalk.backend.staff.repository.StaffPatientAssignmentRepository;
import com.looktalk.backend.staff.repository.StaffRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.support.DisplayNameResolver;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6~7. EMERGENCY-001~003 + Emergency WebSocket
@Service
public class EmergencyCallService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final EmergencyCallRepository emergencyCallRepository;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final StaffPatientAssignmentRepository assignmentRepository;
    private final EmergencyRealtimePublisher realtimePublisher;
    private final TransactionTemplate transactionTemplate;

    public EmergencyCallService(
            EmergencyCallRepository emergencyCallRepository,
            UserRepository userRepository,
            StaffRepository staffRepository,
            StaffPatientAssignmentRepository assignmentRepository,
            EmergencyRealtimePublisher realtimePublisher,
            PlatformTransactionManager transactionManager
    ) {
        this.emergencyCallRepository = emergencyCallRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.realtimePublisher = realtimePublisher;

        // EMERGENCY-001은 DB commit 이후에만 WebSocket publish를 수행해야 한다(ChatMessageService와 동일한
        // 원칙). 메서드 전체를 @Transactional로 두면 프록시 commit이 메서드 반환 시점에 일어나 publish가
        // 커밋 이전에 실행되므로, 저장 단계만 명시적으로 감싸는 TransactionTemplate을 사용한다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // EMERGENCY-001
    public EmergencyCallCreateResponse createCall(String userId) {
        User patient = findPatient(userId);

        EmergencyCall saved = transactionTemplate.execute(status -> {
            EmergencyCall call = EmergencyCall.builder()
                    .patientUser(patient)
                    .calledAt(Instant.now())
                    .build();
            return emergencyCallRepository.save(call);
        });

        // transactionTemplate.execute()가 정상 반환한 시점은 이미 commit이 완료된 이후다. WebSocket publish는
        // 반드시 이 뒤에서만 수행한다. 담당 STAFF가 0명이어도 호출 저장(및 이 REST 응답)은 이미 성공했다.
        String patientDisplayName = DisplayNameResolver.resolve(patient);
        EmergencyCallCreatedEventData eventData = new EmergencyCallCreatedEventData(
                saved.getEmergencyCallId(), patient.getUserId(), patientDisplayName, saved.getCalledAt());
        EmergencyWebSocketEvent event = EmergencyWebSocketEvent.emergencyCallCreated(eventData);

        for (String staffUserId : findAssignedStaffUserIds(patient.getUserId())) {
            realtimePublisher.publishEmergencyCallCreated(staffUserId, event);
        }

        return new EmergencyCallCreateResponse(saved.getEmergencyCallId(), saved.getCalledAt());
    }

    private List<String> findAssignedStaffUserIds(String patientUserId) {
        return assignmentRepository.findAllByPatientUser_UserId(patientUserId).stream()
                .map(StaffPatientAssignment::getStaff)
                .map(Staff::getUser)
                .map(User::getUserId)
                .toList();
    }

    // EMERGENCY-002
    @Transactional(readOnly = true)
    public EmergencyCallPageResponse getAssignedCalls(String userId, Integer page, Integer size, boolean unreadOnly) {
        Staff staff = findStaff(userId);

        int resolvedPage = resolvePage(page);
        int resolvedSize = resolveSize(size);

        Page<EmergencyCall> result = emergencyCallRepository.findAssignedCalls(
                staff.getStaffId(), unreadOnly, PageRequest.of(resolvedPage, resolvedSize));

        List<EmergencyCallListItemResponse> items = result.getContent().stream()
                .map(this::toListItem)
                .toList();

        return new EmergencyCallPageResponse(
                items, result.getNumber(), result.getSize(), result.getTotalElements(), result.hasNext());
    }

    // EMERGENCY-003 - 담당 환자의 호출만 읽음 처리하고, 미담당/존재하지 않는 호출은 동일하게
    // EMERGENCY_CALL_NOT_FOUND로 처리해 존재 여부를 노출하지 않는다.
    @Transactional
    public EmergencyCallReadResponse markRead(String userId, Long emergencyCallId) {
        Staff staff = findStaff(userId);
        Instant now = Instant.now();

        int updated = emergencyCallRepository.markReadIfAssignedAndUnread(
                emergencyCallId, staff, staff.getStaffId(), now);

        EmergencyCall call = updated == 1
                ? reloadAfterUpdate(emergencyCallId)
                : emergencyCallRepository.findByIdAssignedToStaff(emergencyCallId, staff.getStaffId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_CALL_NOT_FOUND));

        return new EmergencyCallReadResponse(
                call.getEmergencyCallId(),
                call.getReadAt(),
                call.getReadByStaff() == null ? null : call.getReadByStaff().getStaffId()
        );
    }

    private EmergencyCall reloadAfterUpdate(Long emergencyCallId) {
        return emergencyCallRepository.findById(emergencyCallId)
                .orElseThrow(() -> new IllegalStateException(
                        "emergencyCallId " + emergencyCallId + "를 읽음 처리 직후 조회하지 못했습니다."));
    }

    private EmergencyCallListItemResponse toListItem(EmergencyCall call) {
        User patient = call.getPatientUser();

        return new EmergencyCallListItemResponse(
                call.getEmergencyCallId(),
                patient.getUserId(),
                DisplayNameResolver.resolve(patient),
                call.getCalledAt(),
                call.getReadAt(),
                call.getReadByStaff() == null ? null : call.getReadByStaff().getStaffId(),
                call.getReadAt() == null
        );
    }

    private int resolvePage(Integer page) {
        int resolved = page == null ? 0 : page;
        if (resolved < 0) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
        return resolved;
    }

    private int resolveSize(Integer size) {
        int resolved = size == null ? DEFAULT_SIZE : size;
        if (resolved < MIN_SIZE || resolved > MAX_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }
        return resolved;
    }

    // docs/LookTalk_Backend_Phase2_Implementation_Plan.md 19. Security 권한 - Spring Security URL 규칙과
    // 별개로 Service에서도 역할을 다시 검증한다.
    private User findPatient(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        return user;
    }

    private Staff findStaff(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() != UserRole.STAFF) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        return staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_NOT_FOUND));
    }
}
