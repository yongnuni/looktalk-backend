package com.looktalk.backend.staff.service;

import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.staff.dto.StaffMeResponse;
import com.looktalk.backend.staff.dto.StaffPatientAliasUpdateRequest;
import com.looktalk.backend.staff.dto.StaffPatientResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {

    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final StaffPatientAssignmentRepository assignmentRepository;

    public StaffService(
            StaffRepository staffRepository,
            UserRepository userRepository,
            StaffPatientAssignmentRepository assignmentRepository
    ) {
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
    }

    // STAFF-001
    @Transactional(readOnly = true)
    public StaffMeResponse getMe(String userId) {
        Staff staff = findStaffByUserId(userId);
        User user = staff.getUser();

        return new StaffMeResponse(
                staff.getStaffId(),
                user.getUserId(),
                user.getLoginId(),
                user.getUserEmail(),
                user.getUserName(),
                DisplayNameResolver.resolve(user),
                new StaffMeResponse.HospitalSummary(
                        staff.getHospital().getHospitalId(),
                        staff.getHospital().getHospitalName()
                )
        );
    }

    // STAFF-002
    @Transactional(readOnly = true)
    public List<StaffPatientResponse> getPatients(String userId) {
        Staff staff = findStaffByUserId(userId);

        return assignmentRepository.findAllByStaff_StaffIdOrderByAssignedAtDesc(staff.getStaffId())
                .stream()
                .map(this::toPatientResponse)
                .toList();
    }

    private StaffPatientResponse toPatientResponse(StaffPatientAssignment assignment) {
        User patient = assignment.getPatientUser();

        return new StaffPatientResponse(
                patient.getUserId(),
                patient.getLoginId(),
                patient.getUserName(),
                resolveAliasAwareDisplayName(assignment, patient),
                assignment.getPatientAlias(),
                assignment.getAssignedAt()
        );
    }

    // docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 14. patientAlias -> userName -> loginId.
    // 전역 DisplayNameResolver(userName -> loginId)는 건드리지 않고, "현재 STAFF가 자기 담당 PATIENT를 보는"
    // 이 문맥에서만 assignment의 alias를 우선한다.
    private String resolveAliasAwareDisplayName(StaffPatientAssignment assignment, User patient) {
        String alias = assignment.getPatientAlias();
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        return DisplayNameResolver.resolve(patient);
    }

    // STAFF-003
    @Transactional
    public void addPatient(String userId, String patientLoginId) {
        Staff staff = findStaffByUserId(userId);

        User patient = userRepository.findByLoginId(patientLoginId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_PATIENT_NOT_FOUND));

        if (patient.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.STAFF_PATIENT_NOT_FOUND);
        }

        if (assignmentRepository.existsByStaff_StaffIdAndPatientUser_UserId(staff.getStaffId(), patient.getUserId())) {
            throw new BusinessException(ErrorCode.STAFF_PATIENT_ALREADY_ASSIGNED);
        }

        StaffPatientAssignment assignment = StaffPatientAssignment.builder()
                .staff(staff)
                .patientUser(patient)
                .assignedAt(Instant.now())
                .build();
        assignmentRepository.save(assignment);
    }

    // STAFF-004 - 담당 관계 해제 여부는 병원 채팅 사용 권한에 영향을 주지 않는다. 채팅방/메시지는 변경하지 않는다.
    @Transactional
    public void removePatient(String userId, String patientUserId) {
        Staff staff = findStaffByUserId(userId);

        // 현재 staffId와 patientUserId 조합으로만 조회해 다른 의료진의 담당 관계를 삭제할 수 없도록 한다.
        StaffPatientAssignment assignment = assignmentRepository
                .findByStaff_StaffIdAndPatientUser_UserId(staff.getStaffId(), patientUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_PATIENT_NOT_ASSIGNED));

        assignmentRepository.delete(assignment);
    }

    // STAFF-005 - 현재 STAFF 자신의 assignment만 수정한다. staffId+patientUserId 조합으로만 조회해 다른
    // 의료진의 assignment를 잘못 수정할 수 없도록 한다(STAFF-004와 동일한 소유권 검증 패턴).
    // null/공백(trim 후 blank)은 별칭 삭제(NULL 저장)를 의미하고, 그 외에는 trim한 문자열을 저장한다.
    @Transactional
    public StaffPatientResponse updatePatientAlias(String userId, String patientUserId, StaffPatientAliasUpdateRequest request) {
        Staff staff = findStaffByUserId(userId);

        StaffPatientAssignment assignment = assignmentRepository
                .findByStaff_StaffIdAndPatientUser_UserId(staff.getStaffId(), patientUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_PATIENT_NOT_ASSIGNED));

        String trimmedAlias = request.alias() == null ? null : request.alias().trim();
        assignment.setPatientAlias(trimmedAlias == null || trimmedAlias.isBlank() ? null : trimmedAlias);

        return toPatientResponse(assignment);
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 33~36 - STAFF 전용 API.
    // Spring Security URL 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private Staff findStaffByUserId(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() != UserRole.STAFF) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }

        return staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_NOT_FOUND));
    }
}
