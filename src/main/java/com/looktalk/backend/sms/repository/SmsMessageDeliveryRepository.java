package com.looktalk.backend.sms.repository;

import com.looktalk.backend.sms.entity.SmsMessageDelivery;
import com.looktalk.backend.sms.entity.SmsProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsMessageDeliveryRepository extends JpaRepository<SmsMessageDelivery, Long> {

    // SOLAPI SINGLE-REPORT webhook이 provider messageId로 이 행을 역조회할 때 사용한다.
    Optional<SmsMessageDelivery> findByProviderAndProviderMessageId(SmsProvider provider, String providerMessageId);

    Optional<SmsMessageDelivery> findByMessage_MessageId(Long messageId);
}
