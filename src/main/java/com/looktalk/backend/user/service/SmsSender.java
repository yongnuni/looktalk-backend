package com.looktalk.backend.user.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003 - "SMS Provider를 통해 원본 인증번호를 발송한다."
// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER)로 구현체가 교체된다(LoggingSmsSender/SolapiSmsSender).
// 발송 실패 시 com.looktalk.backend.chat.service.SmsSendFailedException을 던진다 - chat 패키지에 있지만
// "SMS Provider 발송 실패"라는 의미 자체는 OTP/chat 공용이라 새 예외 타입을 추가하지 않고 재사용한다.
public interface SmsSender {

    void sendVerificationCode(String phone, String code);
}
