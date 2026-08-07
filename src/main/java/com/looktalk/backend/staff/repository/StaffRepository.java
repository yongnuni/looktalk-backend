package com.looktalk.backend.staff.repository;

import com.looktalk.backend.staff.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<Staff, Long> {
}
