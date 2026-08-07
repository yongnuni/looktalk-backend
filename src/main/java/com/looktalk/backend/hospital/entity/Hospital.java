package com.looktalk.backend.hospital.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "hospital")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Hospital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hospital_id")
    private Long hospitalId;

    // [화면 6] 의료진 "내 정보 수정" 화면의 "병원 정보"
    @Column(name = "hospital_name", length = 100, nullable = false)
    private String hospitalName;

    // [화면 3] 의료진 회원가입 화면의 "초대 코드"
    @Column(name = "invite_code", length = 50, nullable = false, unique = true)
    private String inviteCode;

    @Column(name = "created_at")
    private Instant createdAt;
}
