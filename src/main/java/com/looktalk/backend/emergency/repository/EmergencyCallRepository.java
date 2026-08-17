package com.looktalk.backend.emergency.repository;

import com.looktalk.backend.emergency.entity.EmergencyCall;
import com.looktalk.backend.staff.entity.Staff;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmergencyCallRepository extends JpaRepository<EmergencyCall, Long> {

    // EMERGENCY-002 - staff_patient_assignment로 현재 STAFF가 담당하는 환자의 호출만 조회한다.
    // called_at DESC, 동일 시각이면 emergency_call_id DESC로 정렬한다. patientUser는 to-one fetch join이라
    // pagination 결과 개수에 영향을 주지 않는다.
    @Query(
            value = "select e from EmergencyCall e join fetch e.patientUser "
                    + "where e.patientUser.userId in ("
                    + "  select a.patientUser.userId from StaffPatientAssignment a where a.staff.staffId = :staffId"
                    + ") "
                    + "and (:unreadOnly = false or e.readAt is null) "
                    + "order by e.calledAt desc, e.emergencyCallId desc",
            countQuery = "select count(e) from EmergencyCall e "
                    + "where e.patientUser.userId in ("
                    + "  select a.patientUser.userId from StaffPatientAssignment a where a.staff.staffId = :staffId"
                    + ") "
                    + "and (:unreadOnly = false or e.readAt is null)"
    )
    Page<EmergencyCall> findAssignedCalls(
            @Param("staffId") Long staffId,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable
    );

    // EMERGENCY-003 - "담당 환자의 호출이고 아직 읽지 않은 경우"에만 원자적으로 갱신한다.
    // read_at IS NULL을 WHERE에 포함해, 동시에 두 STAFF가 요청해도 먼저 commit된 UPDATE만 성공하고
    // 이후 요청은 이 WHERE 재평가에서 걸려 0건 갱신되므로 최초 reader가 덮어써지지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update EmergencyCall e set e.readAt = :now, e.readByStaff = :staff "
                    + "where e.emergencyCallId = :id and e.readAt is null "
                    + "and e.patientUser.userId in ("
                    + "  select a.patientUser.userId from StaffPatientAssignment a where a.staff.staffId = :staffId"
                    + ")"
    )
    int markReadIfAssignedAndUnread(
            @Param("id") Long id,
            @Param("staff") Staff staff,
            @Param("staffId") Long staffId,
            @Param("now") Instant now
    );

    // EMERGENCY-003 - 위 조건부 UPDATE가 0건일 때 원인을 구분하기 위한 조회.
    // "존재하지 않음"과 "담당하지 않는 호출"을 동일하게 취급해 노출하지 않고,
    // "담당은 맞지만 이미 다른 STAFF가 읽음"인 경우만 걸러내 기존 read 정보를 반환한다.
    @Query(
            "select e from EmergencyCall e where e.emergencyCallId = :id "
                    + "and e.patientUser.userId in ("
                    + "  select a.patientUser.userId from StaffPatientAssignment a where a.staff.staffId = :staffId"
                    + ")"
    )
    Optional<EmergencyCall> findByIdAssignedToStaff(@Param("id") Long id, @Param("staffId") Long staffId);
}
