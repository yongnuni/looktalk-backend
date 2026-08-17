package com.looktalk.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 8, 11. 이메일 실발송
// JavaMailSender를 mock해 실제 외부 SMTP 없이 SmtpEmailSender의 계약(수신자/제목/본문/실패 처리/로그 비노출/
// 필수 설정 fail-fast)을 결정적으로 검증한다.
@ExtendWith(MockitoExtension.class)
class SmtpEmailSenderTest {

    private static final String CODE = "482913";
    private static final String VALID_HOST = "smtp.example.com";
    private static final String VALID_FROM = "no-reply@looktalk.local";
    private static final String VALID_USERNAME = "smtp-user";
    private static final String VALID_PASSWORD = "smtp-pass";

    @Mock
    private JavaMailSender javaMailSender;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger smtpEmailSenderLogger;

    @BeforeEach
    void setUp() {
        smtpEmailSenderLogger = (Logger) LoggerFactory.getLogger(SmtpEmailSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        smtpEmailSenderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        smtpEmailSenderLogger.detachAppender(logAppender);
    }

    private SmtpEmailSender validSender() {
        return new SmtpEmailSender(
                javaMailSender, VALID_FROM, VALID_HOST, true, VALID_USERNAME, VALID_PASSWORD, 300);
    }

    @Test
    void signupMailIsSentWithCorrectRecipientSubjectAndBody() {
        SmtpEmailSender sender = validSender();

        sender.sendVerificationCode("patient@example.com", CODE, VerificationPurpose.SIGNUP);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertEquals(VALID_FROM, sent.getFrom());
        assertEquals(1, sent.getTo().length);
        assertEquals("patient@example.com", sent.getTo()[0]);
        assertEquals("LookTalk 회원가입 이메일 인증", sent.getSubject());
        assertTrue(sent.getText().contains(CODE), "본문에 인증번호가 포함되어야 한다.");
        assertTrue(sent.getText().contains("5분"), "300초 OTP 만료시간이 5분으로 반영되어야 한다.");
    }

    @Test
    void passwordResetMailIsSentWithDistinctSubject() {
        SmtpEmailSender sender = validSender();

        sender.sendVerificationCode("patient@example.com", CODE, VerificationPurpose.PASSWORD_RESET);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertEquals("LookTalk 비밀번호 재설정 인증", sent.getSubject());
        assertTrue(sent.getText().contains(CODE));
    }

    @Test
    void smtpSendFailureMapsToEmailSendFailedBusinessException() {
        SmtpEmailSender sender = validSender();
        doThrow(new MailSendException("connection refused")).when(javaMailSender).send(any(SimpleMailMessage.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sender.sendVerificationCode("patient@example.com", CODE, VerificationPurpose.SIGNUP));

        assertEquals(ErrorCode.EMAIL_SEND_FAILED, exception.getErrorCode());
    }

    @Test
    void verificationCodeIsNeverWrittenToLogsEvenOnFailure() {
        SmtpEmailSender sender = validSender();
        doThrow(new MailSendException("connection refused")).when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThrows(BusinessException.class,
                () -> sender.sendVerificationCode("patient@example.com", CODE, VerificationPurpose.SIGNUP));

        boolean anyLogContainsCode = logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(CODE));
        assertFalse(anyLogContainsCode, "실패 로그를 포함한 어떤 로그에도 인증번호 원문이 남으면 안 된다.");
    }

    // ===== 필수 설정 fail-fast (배포 안전성) =====

    @Test
    void constructorFailsFastWhenHostIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEmailSender(
                javaMailSender, VALID_FROM, "", true, VALID_USERNAME, VALID_PASSWORD, 300));
    }

    @Test
    void constructorFailsFastWhenFromAddressIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEmailSender(
                javaMailSender, "", VALID_HOST, true, VALID_USERNAME, VALID_PASSWORD, 300));
    }

    @Test
    void constructorFailsFastWhenSmtpAuthEnabledButUsernameBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEmailSender(
                javaMailSender, VALID_FROM, VALID_HOST, true, "", VALID_PASSWORD, 300));
    }

    @Test
    void constructorFailsFastWhenSmtpAuthEnabledButPasswordBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEmailSender(
                javaMailSender, VALID_FROM, VALID_HOST, true, VALID_USERNAME, "", 300));
    }

    @Test
    void constructorSucceedsWhenSmtpAuthDisabledEvenWithoutUsernameAndPassword() {
        assertDoesNotThrow(() -> new SmtpEmailSender(
                javaMailSender, VALID_FROM, VALID_HOST, false, "", "", 300));
    }
}
