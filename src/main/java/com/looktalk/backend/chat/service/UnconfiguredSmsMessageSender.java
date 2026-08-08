package com.looktalk.backend.chat.service;

import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS
// 실제 SMS Provider 업체가 아직 확정되지 않았다. "로컬이니까 무조건 성공"하는 fake Provider를 만들지 않고,
// 모든 프로필(local 포함)에서 정직하게 미설정 상태(SMS_PROVIDER_NOT_CONFIGURED)를 반환한다.
// Provider가 확정되면 이 구현체를 실제 Provider 연동으로 교체한다.
@Component
public class UnconfiguredSmsMessageSender implements SmsMessageSender {

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void send(String phone, String content) {
        throw new SmsSendFailedException("SMS Provider가 아직 설정되지 않았습니다.");
    }
}
