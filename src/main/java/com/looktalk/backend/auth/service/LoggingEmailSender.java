package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.entity.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// local/test용 구현체. 실제 발송 대신 발송 사실만 기록한다.
// docs/LookTalk_Backend_API_Spec_v2.1.md 5.4, 68. - 이메일 주소와 인증번호 원문은 로그에 남기지 않는다.
// looktalk.mail.provider(MAIL_PROVIDER)=logging일 때만 활성화된다. matchIfMissing을 쓰지 않는다 - property가
// 아예 없는 배포 환경(local profile이 아닌 환경에서 MAIL_PROVIDER를 깜빡한 경우)에서 이 구현체로 조용히
// fallback되면 안 되기 때문이다. local profile에서는 application-local.yml이 기본값(logging)을 제공한다.
@Component
@ConditionalOnProperty(prefix = "looktalk.mail", name = "provider", havingValue = "logging")
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendVerificationCode(String email, String code, VerificationPurpose purpose) {
        log.info("이메일 인증번호 발송 요청을 처리했습니다. purpose={}", purpose);
    }
}
