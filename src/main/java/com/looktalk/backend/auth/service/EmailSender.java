package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.entity.VerificationPurpose;

// docs/LookTalk_Backend_API_Spec_v2.1.md 17. AUTH-001 - "메일 Provider를 호출해 인증번호를 발송한다."
// 실제 메일 Provider는 아직 확정되지 않았다. 구현체는 계약만 만족하면 교체 가능하다.
public interface EmailSender {

    void sendVerificationCode(String email, String code, VerificationPurpose purpose);
}
