package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.entity.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 실제 메일 Provider가 아직 선정되지 않아 발송 사실만 기록하는 임시 구현체.
// docs/LookTalk_Backend_API_Spec_v2.1.md 5.4, 68. - 이메일 주소와 인증번호 원문은 로그에 남기지 않는다.
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendVerificationCode(String email, String code, VerificationPurpose purpose) {
        log.info("이메일 인증번호 발송 요청을 처리했습니다. purpose={}", purpose);
    }
}
