package com.looktalk.backend.user.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

// docs/LookTalk_Backend_API_Spec_v2.1.md 5.4, 68 - OTP code/전화번호 원문을 로그에 남기지 않는다는 요구사항을
// 실제 로그 output으로 직접 검증한다(§29 절대 로그 금지 목록).
class LoggingSmsSenderTest {

    private static final String PHONE = "01099998888";
    private static final String CODE = "482913";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void sendVerificationCodeNeverLogsCodeOrPhone() {
        new LoggingSmsSender().sendVerificationCode(PHONE, CODE);

        assertTrue(appender.list.size() >= 1, "발송 시도 자체는 로그로 남아야 한다.");
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains(CODE), "OTP code가 로그에 노출되었습니다: " + message);
            assertFalse(message.contains(PHONE), "전화번호 원문이 로그에 노출되었습니다: " + message);
        }
    }
}
