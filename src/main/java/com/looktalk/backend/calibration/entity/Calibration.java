package com.looktalk.backend.calibration.entity;

import com.looktalk.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 10.1 - 사용자별 공통 gaze calibration
// (iris -> RAW_HOMOGRAPHY -> NORMALIZED_VIEWPORT). 입력 방식(EYE_TRACKING/BLINK/MOUTH)별로 분리되지
// 않으므로 input_method 컬럼을 두지 않는다. calibrationId는 Backend가 UUID 문자열로 직접 생성한다.
@Entity
@Table(name = "calibration")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Calibration {

    @Id
    @Column(name = "calibration_id", length = 100)
    private String calibrationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "algorithm_version", length = 100, nullable = false)
    private String algorithmVersion;

    // v1 RAW_HOMOGRAPHY + NORMALIZED_VIEWPORT envelope을 그대로 저장한다. 구조 validation은
    // CalibrationDataValidator가 저장 전에 수행하며, 이 필드는 검증된 payload의 원본 표현을 보존한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calibration_data", nullable = false, columnDefinition = "jsonb")
    private JsonNode calibrationData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
