package com.looktalk.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "user_name", length = 50, nullable = false)
    private String userName;

    @Column(name = "user_email", length = 100, nullable = false, unique = true)
    private String userEmail;

    @Column(name = "user_pwd", length = 255, nullable = false)
    private String userPwd;

    @Column(name = "user_phone", length = 20)
    private String userPhone;

    @Column(name = "profile_image", columnDefinition = "text")
    private String profileImage;

    @Column(name = "is_email_verified")
    private Boolean isEmailVerified;

    @Column(name = "is_sms_verified")
    private Boolean isSmsVerified;

    @Column(name = "reset_token", length = 255)
    private String resetToken;

    @Column(name = "reset_token_expiry")
    private Instant resetTokenExpiry;

    @Column(name = "created_at")
    private Instant createdAt;

    // [임시결정 1] 로그인에 쓰는 "아이디". user_email과 별개 필드.
    @Column(name = "login_id", length = 50, unique = true)
    private String loginId;

    // [임시결정 7] 역할 구분. users.role + staff/patient 테이블 병행.
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20)
    private UserRole role;
}
