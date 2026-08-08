package com.looktalk.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// docs/LookTalk_Backend_API_Spec_v2.1.md 20. AUTH-004 - 회원가입 시 users와 하나의 Transaction으로 함께 생성한다.
@Entity
@Table(name = "user_setting")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserSetting {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "keyboard_layout", length = 20)
    private String keyboardLayout;

    @Column(name = "is_key_enlarged")
    private Boolean isKeyEnlarged;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_input_method", length = 30)
    private InputMethod currentInputMethod;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
