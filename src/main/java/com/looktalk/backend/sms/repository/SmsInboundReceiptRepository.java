package com.looktalk.backend.sms.repository;

import com.looktalk.backend.sms.entity.SmsInboundReceipt;
import com.looktalk.backend.sms.entity.SmsProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsInboundReceiptRepository extends JpaRepository<SmsInboundReceipt, Long> {

    // moKey(=externalMessageId) idempotency 검사. ux_sms_inbound_receipt_provider_external_id(V10) UNIQUE
    // 인덱스가 동시 요청 경합 상황에서도 최종 방어선이 된다.
    boolean existsByProviderAndExternalMessageId(SmsProvider provider, String externalMessageId);
}
