
package com.looktalk.backend.inputsession.entity;

import com.looktalk.backend.calibration.entity.Calibration;
import com.looktalk.backend.user.entity.InputMethod;
import com.looktalk.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "input_session_log")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InputSession {

    @Id
    @Column(name = "log_id", length = 100)
    private String logId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calibration_id")
    private Calibration calibration;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_method", length = 30, nullable = false)
    private InputMethod inputMethod;

    @Column(name = "session_type", length = 30, nullable = false)
    private String sessionType;

    @Column(name = "keyboard_layout", length = 20, nullable = false)
    private String keyboardLayout;

    @Column(name = "typo_rate")
    private Float typoRate;

    @Column(name = "input_speed")
    private Float inputSpeed;

    @Column(name = "recognition_accuracy")
    private Float recognitionAccuracy;

    @Column(name = "input_stability")
    private Float inputStability;

    @Column(name = "session_at", insertable = false, updatable = false)
    private Instant sessionAt;
}
