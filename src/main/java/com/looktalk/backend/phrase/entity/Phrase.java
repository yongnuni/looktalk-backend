package com.looktalk.backend.phrase.entity;

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

// docs/LookTalk_Backend_API_Spec_v2.1.md 44~47. PHRASE-001~004
// phrase_text 컬럼은 V6 이후 평문이 아닌 E2EE Base64 ciphertext만 저장한다.
@Entity
@Table(name = "phrase")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Phrase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "phrase_id")
    private Long phraseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "phrase_text", nullable = false, columnDefinition = "text")
    private String ciphertext;

    @Column(name = "category", length = 30)
    private String category;

    @Column(name = "key_version", nullable = false)
    private Integer keyVersion;

    @Column(name = "encryption_algorithm", nullable = false, length = 40)
    private String encryptionAlgorithm;

    @Column(name = "created_at")
    private Instant createdAt;
}
