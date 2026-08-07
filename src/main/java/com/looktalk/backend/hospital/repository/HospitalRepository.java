package com.looktalk.backend.hospital.repository;

import com.looktalk.backend.hospital.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {
}
