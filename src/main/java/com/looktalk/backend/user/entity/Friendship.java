package com.looktalk.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "friendship")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "friendship_id")
    private Long friendshipId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "friend_user_id")
    private User friendUser;

    // status는 DB에 CHECK 제약이 없고 값 집합이 확정되지 않아 enum이 아닌 String으로 매핑함
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    // [임시결정 6] 친구 비회원 허용 — 팀 회의 확정 전
    @Column(name = "friend_name", length = 50)
    private String friendName;

    // [임시결정 5, 6] 친구 비회원 허용 — 팀 회의 확정 전
    @Column(name = "friend_phone", length = 20)
    private String friendPhone;
}
