package com.looktalk.backend.chat.entity;

import com.looktalk.backend.user.entity.User;
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

// docs/LookTalk_Backend_API_Spec_v2.1.md 52~55. CHAT-001~004
// 회원 participant는 user를 사용하고, 외부 SMS 지인 participant는 user=NULL + externalName/externalPhone을 사용한다.
// 내부 회원이 탈퇴하면(V7) user=NULL + isWithdrawn=true로 전환되며, chat_room/message 이력을 보존하기 위해
// participant 행 자체는 삭제하지 않는다. chk_chat_participant_identity(V7)가 이 세 가지 상태만 허용한다.
@Entity
@Table(name = "chat_participant")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ChatParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long participantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "external_name", length = 50)
    private String externalName;

    @Column(name = "external_phone", length = 20)
    private String externalPhone;

    @Column(name = "joined_at")
    private Instant joinedAt;

    // V7 - 내부 회원 탈퇴로 user_id가 NULL로 정리된 participant인지 여부.
    @Column(name = "is_withdrawn", nullable = false)
    private boolean withdrawn;
}
