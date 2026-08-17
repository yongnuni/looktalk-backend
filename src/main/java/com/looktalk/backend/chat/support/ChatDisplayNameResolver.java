package com.looktalk.backend.chat.support;

import com.looktalk.backend.staff.entity.StaffPatientAssignment;
import com.looktalk.backend.staff.repository.StaffPatientAssignmentRepository;
import com.looktalk.backend.staff.repository.StaffRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.support.DisplayNameResolver;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 14, Step 8B - Chat REST/WebSocket 전용
// recipient(viewer)별 표시명 규칙. 전역 DisplayNameResolver(userName -> loginId)는 viewer-independent로
// 그대로 두고, "지금 보고 있는 사람이 STAFF이고 대상이 자신의 담당 PATIENT인 경우에만" patientAlias를
// 우선한다. PATIENT viewer나 STAFF 대상에게는 이 규칙이 전혀 적용되지 않고 항상 전역 규칙으로 fallback한다.
@Component
public class ChatDisplayNameResolver {

    private final StaffRepository staffRepository;
    private final StaffPatientAssignmentRepository assignmentRepository;

    public ChatDisplayNameResolver(
            StaffRepository staffRepository, StaffPatientAssignmentRepository assignmentRepository) {
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
    }

    // CHAT-001/002/005 - viewer가 STAFF일 때, 후보 PATIENT userId 집합에 대한 자신의 alias를 한 번에 배치
    // 조회한다(패턴당 N+1 방지). viewer가 PATIENT거나 STAFF row가 없으면 DB 호출 없이 빈 map을 반환해,
    // 호출부가 항상 전역 규칙으로 fallback하도록 한다.
    public Map<String, String> loadAliasesForStaffViewer(User viewer, Collection<String> candidatePatientUserIds) {
        if (viewer.getRole() != UserRole.STAFF || candidatePatientUserIds.isEmpty()) {
            return Map.of();
        }
        return staffRepository.findByUser_UserId(viewer.getUserId())
                .map(staff -> assignmentRepository
                        .findAllByStaff_StaffIdAndPatientUser_UserIdIn(staff.getStaffId(), candidatePatientUserIds)
                        .stream()
                        .filter(a -> hasAlias(a.getPatientAlias()))
                        .collect(Collectors.toMap(
                                a -> a.getPatientUser().getUserId(), StaffPatientAssignment::getPatientAlias)))
                .orElse(Map.of());
    }

    // WebSocket HOSPITAL 발송 - sender가 PATIENT일 때, 여러 STAFF recipient 각자의 alias를 한 번에 배치
    // 조회한다(recipient마다 개별 쿼리하지 않기 위함). sender가 STAFF면 DB 호출 없이 빈 map을 반환한다.
    public Map<String, String> loadAliasesForPatientSender(User sender, Collection<String> staffRecipientUserIds) {
        if (sender.getRole() != UserRole.PATIENT || staffRecipientUserIds.isEmpty()) {
            return Map.of();
        }
        return assignmentRepository
                .findAllByPatientUser_UserIdAndStaffUserIdIn(sender.getUserId(), staffRecipientUserIds)
                .stream()
                .filter(a -> hasAlias(a.getPatientAlias()))
                .collect(Collectors.toMap(a -> a.getStaff().getUser().getUserId(), StaffPatientAssignment::getPatientAlias));
    }

    // alias가 있으면 그대로, 없으면 전역 규칙(userName -> loginId)으로 fallback한다.
    public String withAliasFallback(String alias, User fallbackTarget) {
        return hasAlias(alias) ? alias : DisplayNameResolver.resolve(fallbackTarget);
    }

    private boolean hasAlias(String alias) {
        return alias != null && !alias.isBlank();
    }
}
