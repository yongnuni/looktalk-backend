package com.looktalk.backend.sms.entity;

import com.looktalk.backend.chat.entity.Message;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// V10 sms_inbound_receipt - U+ MO inbound 처리 idempotency 기록. moMsg 평문은 어떤 컬럼에도 저장하지 않는다
// (실제 본문은 message_ciphertext에만 암호문으로 남는다).
@Entity
@Table(name = "sms_inbound_receipt")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SmsInboundReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inbound_receipt_id")
    private Long inboundReceiptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SmsProvider provider;

    // U+ MO의 moKey.
    @Column(name = "external_message_id", nullable = false, length = 100)
    private String externalMessageId;

    @Column(name = "service_number", nullable = false, length = 20)
    private String serviceNumber;

    @Column(name = "sender_phone", nullable = false, length = 20)
    private String senderPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private SmsInboundProcessingStatus processingStatus;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
