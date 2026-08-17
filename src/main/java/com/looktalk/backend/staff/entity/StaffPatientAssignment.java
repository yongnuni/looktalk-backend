package com.looktalk.backend.staff.entity;

import com.looktalk.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// docs/LookTalk_Backend_API_Spec_v2.1.md 34~36. STAFF-002~004
// 담당 환자 관리 기능에만 사용하고 채팅 권한에는 사용하지 않는다.
@Entity
@Table(name = "staff_patient_assignment")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StaffPatientAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false)
    private Staff staff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_user_id", nullable = false)
    private User patientUser;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    // docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 10.4 - 특정 STAFF가 자기 담당 PATIENT를
    // 자신의 화면에서만 보기 위한 개인 별칭. users.user_name을 덮어쓰지 않으며, 이 assignment의 소유자인
    // STAFF에게만 노출한다.
    @Column(name = "patient_alias", length = 50)
    private String patientAlias;
}
