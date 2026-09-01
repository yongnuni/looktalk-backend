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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// V10 sms_message_delivery - SMS 친구채팅(outbound) 메시지 1건의 Provider 발송/배달 상태. message와 1:1이며
// HOSPITAL 메시지에는 이 행이 없다(message 테이블에 SMS 전용 컬럼을 추가하지 않기 위해 분리했다).
@Entity
@Table(name = "sms_message_delivery")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SmsMessageDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sms_delivery_id")
    private Long smsDeliveryId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SmsProvider provider;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private SmsDeliveryStatus deliveryStatus;

    @Column(name = "provider_status_code", length = 20)
    private String providerStatusCode;

    @Column(name = "provider_status_message", length = 200)
    private String providerStatusMessage;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
