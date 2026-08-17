package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

// 실제 배포 환경용 구현체. Jakarta Mail 세부사항은 Spring의 JavaMailSender에 위임하고, 여기서는 인증 메일의
// 수신자/제목/본문 조립과 실패 처리만 담당한다. AuthService/VerificationCodeService는 이 구현체의 존재를 모른다.
// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 8. - looktalk.mail.provider(MAIL_PROVIDER)=smtp일 때만
// 활성화되어 LoggingEmailSender와 동시에 Bean으로 등록되지 않는다.
@Component
@ConditionalOnProperty(prefix = "looktalk.mail", name = "provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender javaMailSender;
    private final String from;
    private final long otpExpirySeconds;

    // provider=smtp로 선택되었는데 실제 발송에 필요한 필수 값이 비어 있으면 애플리케이션 기동 단계에서 즉시
    // 실패시킨다(런타임 503 ErrorCode를 추가하는 대신 Spring Bean 생성 실패로 misconfiguration을 막는다).
    // host/from은 항상 필수다. username/password는 spring.mail.properties.mail.smtp.auth(MAIL_SMTP_AUTH)가
    // true일 때만(JavaMailSender가 실제로 인증을 시도하는 경우에만) 필수로 본다. auth=false면 강제하지 않는다.
    public SmtpEmailSender(
            JavaMailSender javaMailSender,
            @Value("${looktalk.mail.from:}") String from,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuthEnabled,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${looktalk.otp.expiry-seconds}") long otpExpirySeconds
    ) {
        Assert.hasText(host, "looktalk.mail.provider=smtp인데 spring.mail.host(MAIL_HOST)가 설정되지 않았습니다.");
        Assert.hasText(from, "looktalk.mail.provider=smtp인데 looktalk.mail.from(MAIL_FROM)이 설정되지 않았습니다.");
        if (smtpAuthEnabled) {
            Assert.hasText(username,
                    "spring.mail.properties.mail.smtp.auth=true인데 spring.mail.username(MAIL_USERNAME)이 설정되지 않았습니다.");
            Assert.hasText(password,
                    "spring.mail.properties.mail.smtp.auth=true인데 spring.mail.password(MAIL_PASSWORD)가 설정되지 않았습니다.");
        }
        this.javaMailSender = javaMailSender;
        this.from = from;
        this.otpExpirySeconds = otpExpirySeconds;
    }

    @Override
    public void sendVerificationCode(String email, String code, VerificationPurpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purposeLabel(purpose));
        message.setText(body(code, purpose));

        try {
            javaMailSender.send(message);
        } catch (MailException exception) {
            // 인증번호 원문/이메일 주소/SMTP 자격증명은 로그에 남기지 않는다. purpose만 남긴다.
            log.warn("이메일 발송에 실패했습니다. purpose={}", purpose, exception);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    private String body(String code, VerificationPurpose purpose) {
        long expiryMinutes = Math.max(1, otpExpirySeconds / 60);

        return purposeLabel(purpose) + "\n\n"
                + "인증번호: " + code + "\n\n"
                + "인증번호는 " + expiryMinutes + "분 후 만료됩니다.";
    }

    private String purposeLabel(VerificationPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> "LookTalk 회원가입 이메일 인증";
            case PASSWORD_RESET -> "LookTalk 비밀번호 재설정 인증";
            case PHONE_VERIFY -> "LookTalk 인증";
        };
    }
}
