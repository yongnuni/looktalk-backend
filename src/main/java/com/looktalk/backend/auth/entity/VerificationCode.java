package com.looktalk.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// [임시결정 2] 이메일/SMS 인증번호를 DB 테이블에 저장 (Redis 등 캐시 계층이 아키텍처에 없음)
@Entity
@Table(name = "verification_code")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_code_id")
    private Long verificationCodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 10, nullable = false)
    private VerificationTargetType targetType;

    @Column(name = "target_value", length = 100, nullable = false)
    private String targetValue;

    @Column(name = "code", length = 10, nullable = false)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at")
    private Instant createdAt;
}
