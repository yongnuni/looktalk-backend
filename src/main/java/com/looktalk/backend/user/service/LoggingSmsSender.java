package com.looktalk.backend.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 실제 SMS Provider가 아직 선정되지 않아 발송 사실만 기록하는 임시 구현체.
// docs/LookTalk_Backend_API_Spec_v2.1.md 5.4, 68. - 전화번호와 인증번호 원문은 로그에 남기지 않는다.
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("SMS 전화번호 인증번호 발송 요청을 처리했습니다.");
    }
}
