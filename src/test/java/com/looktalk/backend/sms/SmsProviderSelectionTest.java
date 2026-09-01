package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.looktalk.backend.chat.service.SmsMessageSender;
import com.looktalk.backend.chat.service.UnconfiguredSmsMessageSender;
import com.looktalk.backend.sms.solapi.SolapiClient;
import com.looktalk.backend.sms.solapi.SolapiSmsMessageSender;
import com.looktalk.backend.sms.solapi.SolapiSmsSender;
import com.looktalk.backend.user.service.LoggingSmsSender;
import com.looktalk.backend.user.service.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER) 기본값(UNCONFIGURED)에서는 LoggingSmsSender/
// UnconfiguredSmsMessageSender만 등록되고 SOLAPI 구현체는 등록되지 않아야 한다(NoUniqueBeanDefinitionException
// 없이 각 interface당 정확히 1개). EmailProviderSelectionTest와 동일한 검증 방식이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmsProviderSelectionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void unconfiguredProvidersAreSelectedByDefault() {
        SmsSender smsSender = applicationContext.getBean(SmsSender.class);
        SmsMessageSender smsMessageSender = applicationContext.getBean(SmsMessageSender.class);

        assertInstanceOf(LoggingSmsSender.class, smsSender);
        assertInstanceOf(UnconfiguredSmsMessageSender.class, smsMessageSender);

        assertTrue(applicationContext.getBeansOfType(SolapiSmsSender.class).isEmpty(),
                "기본 provider(UNCONFIGURED)에서는 SolapiSmsSender가 등록되면 안 된다.");
        assertTrue(applicationContext.getBeansOfType(SolapiSmsMessageSender.class).isEmpty(),
                "기본 provider(UNCONFIGURED)에서는 SolapiSmsMessageSender가 등록되면 안 된다.");
        assertTrue(applicationContext.getBeansOfType(SolapiClient.class).isEmpty(),
                "기본 provider(UNCONFIGURED)에서는 SolapiClient가 등록되면 안 된다.");
    }
}
