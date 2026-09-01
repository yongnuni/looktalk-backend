package com.looktalk.backend.sms.entity;

import com.looktalk.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// V10 sms_inbound_number_assignment - "환자 <-> U+ MO 수신 서비스 번호" 매핑. 번호 자체의 구매/발급은
// 코드 밖(외부 계약/콘솔)에서 이뤄지고, 여기서는 이미 확보한 번호를 환자에게 배정하는 것만 관리한다.
@Entity
@Table(name = "sms_inbound_number_assignment")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SmsInboundNumberAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SmsProvider provider;

    @Column(name = "service_number", nullable = false, length = 20)
    private String serviceNumber;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
