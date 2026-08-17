package com.looktalk.backend.staff.repository;

import com.looktalk.backend.staff.entity.StaffPatientAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffPatientAssignmentRepository extends JpaRepository<StaffPatientAssignment, Long> {

    boolean existsByStaff_StaffIdAndPatientUser_UserId(Long staffId, String patientUserId);

    Optional<StaffPatientAssignment> findByStaff_StaffIdAndPatientUser_UserId(Long staffId, String patientUserId);

    // STAFF-002 - patientUser를 fetch join해 환자 수만큼 반복되는 LAZY load(N+1)를 피한다.
    // 실측 결과 fetch join 없이는 담당 환자 N명 조회 시 users 테이블에 N+1회 SELECT가 발생했다.
    @Query("select a from StaffPatientAssignment a join fetch a.patientUser "
            + "where a.staff.staffId = :staffId order by a.assignedAt desc")
    List<StaffPatientAssignment> findAllByStaff_StaffIdOrderByAssignedAtDesc(@Param("staffId") Long staffId);

    // docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7. Emergency WebSocket - EMERGENCY-001 commit 이후
    // 해당 환자를 담당하는 STAFF 전원의 canonical userId를 조회한다. staff/user를 fetch join해 N+1을 피한다.
    @Query("select a from StaffPatientAssignment a join fetch a.staff s join fetch s.user "
            + "where a.patientUser.userId = :patientUserId")
    List<StaffPatientAssignment> findAllByPatientUser_UserId(@Param("patientUserId") String patientUserId);

    // Step 8B - Chat 조회류(CHAT-001/002/005)에서 "STAFF 1명이 보는 여러 PATIENT"의 alias를 한 번에 조회한다.
    // patientUser를 fetch join해 대상 PATIENT 수만큼 반복 조회(N+1)되지 않게 한다.
    @Query("select a from StaffPatientAssignment a join fetch a.patientUser "
            + "where a.staff.staffId = :staffId and a.patientUser.userId in :patientUserIds")
    List<StaffPatientAssignment> findAllByStaff_StaffIdAndPatientUser_UserIdIn(
            @Param("staffId") Long staffId, @Param("patientUserIds") Collection<String> patientUserIds);

    // Step 8B - Chat WebSocket 발송(HOSPITAL)에서 "PATIENT 1명(sender)을 보는 여러 STAFF recipient" 각자의
    // alias를 한 번에 조회한다. staff.user를 fetch join해 recipient 수만큼 반복 조회(N+1)되지 않게 한다.
    @Query("select a from StaffPatientAssignment a join fetch a.staff s join fetch s.user "
            + "where a.patientUser.userId = :patientUserId and s.user.userId in :staffUserIds")
    List<StaffPatientAssignment> findAllByPatientUser_UserIdAndStaffUserIdIn(
            @Param("patientUserId") String patientUserId, @Param("staffUserIds") Collection<String> staffUserIds);
}
