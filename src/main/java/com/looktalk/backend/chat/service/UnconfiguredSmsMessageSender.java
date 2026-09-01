package com.looktalk.backend.chat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS
// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER)=UNCONFIGURED(기본값)일 때만 활성화된다. "로컬이니까
// 무조건 성공"하는 fake Provider를 만들지 않고, 정직하게 미설정 상태(SMS_PROVIDER_NOT_CONFIGURED)를 반환한다.
// outbound-provider=SOLAPI면 SolapiSmsMessageSender가 대신 선택된다(동시에 등록되지 않는다).
@Component
@ConditionalOnProperty(prefix = "looktalk.sms", name = "outbound-provider", havingValue = "UNCONFIGURED")
public class UnconfiguredSmsMessageSender implements SmsMessageSender {

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public SmsSendResult send(SmsOutboundMessage message) {
        throw new SmsSendFailedException("SMS Provider가 아직 설정되지 않았습니다.");
    }
}
