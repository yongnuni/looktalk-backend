package com.looktalk.backend.sms.repository;

import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsInboundNumberAssignmentRepository extends JpaRepository<SmsInboundNumberAssignment, Long> {

    // outbound SMS 발송 시 "현재 환자에게 배정된 회신 가능 번호"를 찾을 때, inbound webhook에서 moNumber로
    // 환자를 역으로 찾을 때 둘 다 사용한다. ux_sms_inbound_assignment_active_user/...active_service_number
    // (V10) 부분 UNIQUE 인덱스가 각각 최대 1건만 존재함을 보장한다.
    Optional<SmsInboundNumberAssignment> findByUser_UserIdAndActiveTrue(String userId);

    Optional<SmsInboundNumberAssignment> findByServiceNumberAndActiveTrue(String serviceNumber);
}
