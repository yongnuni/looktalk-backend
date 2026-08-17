package com.looktalk.backend.emergency.entity;

import com.looktalk.backend.staff.entity.Staff;
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

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 5.1, 6. EMERGENCY-001~003
// 호출 1회당 1행. readByStaff는 최초로 읽음 처리한 STAFF만 기록하고 이후 다른 STAFF의 요청으로 덮어쓰지 않는다.
@Entity
@Table(name = "emergency_call")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class EmergencyCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "emergency_call_id")
    private Long emergencyCallId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_user_id", nullable = false)
    private User patientUser;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    @Column(name = "read_at")
    private Instant readAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "read_by_staff_id")
    private Staff readByStaff;
}
