package com.looktalk.backend.user.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003 - "SMS Provider를 통해 원본 인증번호를 발송한다."
// 실제 SMS Provider는 아직 확정되지 않았다. 구현체는 계약만 만족하면 교체 가능하다.
// Provider 연동은 예정된 SMS Provider 단계에서 처리한다.
public interface SmsSender {

    void sendVerificationCode(String phone, String code);
}
