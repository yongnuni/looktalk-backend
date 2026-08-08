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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53, 56. CHAT-002/005 - 사용자별 E2EE ciphertext.
// Backend는 평문 메시지를 저장/복호화하지 않고 recipient_user_id로 매칭된 ciphertext만 그대로 반환한다.
@Entity
@Table(name = "message_ciphertext")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MessageCiphertext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_ciphertext_id")
    private Long messageCiphertextId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private User recipientUser;

    @Column(name = "key_version", nullable = false)
    private Integer keyVersion;

    @Column(name = "encryption_algorithm", nullable = false, length = 40)
    private String encryptionAlgorithm;

    @Column(name = "ciphertext", nullable = false, columnDefinition = "text")
    private String ciphertext;
}
