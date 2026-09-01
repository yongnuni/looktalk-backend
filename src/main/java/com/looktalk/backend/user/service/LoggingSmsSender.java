package com.looktalk.backend.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER)=UNCONFIGURED(기본값)일 때만 활성화되는 local/test용
// 구현체. 실제 발송 대신 발송 사실만 기록한다.
// docs/LookTalk_Backend_API_Spec_v2.1.md 5.4, 68. - 전화번호와 인증번호 원문은 로그에 남기지 않는다.
@Component
@ConditionalOnProperty(prefix = "looktalk.sms", name = "outbound-provider", havingValue = "UNCONFIGURED")
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("SMS 전화번호 인증번호 발송 요청을 처리했습니다.");
    }
}
