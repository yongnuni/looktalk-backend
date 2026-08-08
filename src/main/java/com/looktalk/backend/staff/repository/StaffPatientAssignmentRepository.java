package com.looktalk.backend.staff.repository;

import com.looktalk.backend.staff.entity.StaffPatientAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffPatientAssignmentRepository extends JpaRepository<StaffPatientAssignment, Long> {

    boolean existsByStaff_StaffIdAndPatientUser_UserId(Long staffId, String patientUserId);

    Optional<StaffPatientAssignment> findByStaff_StaffIdAndPatientUser_UserId(Long staffId, String patientUserId);

    List<StaffPatientAssignment> findAllByStaff_StaffIdOrderByAssignedAtDesc(Long staffId);
}
