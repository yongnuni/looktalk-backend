package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.looktalk.backend.chat.service.SmsMessageSender;
import com.looktalk.backend.sms.solapi.SolapiClient;
import com.looktalk.backend.sms.solapi.SolapiSmsMessageSender;
import com.looktalk.backend.sms.solapi.SolapiSmsSender;
import com.looktalk.backend.user.service.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

// looktalk.sms.outbound-provider=SOLAPI + 필수 credential이 모두 있을 때 SolapiSmsSender/SolapiSmsMessageSender/
// SolapiClient가 선택되고 Logging/Unconfigured 구현체와 동시에 등록되지 않는지 검증한다. 실제 네트워크 호출은
// 하지 않는다(SolapiClient Bean 생성 자체는 HTTP 호출을 하지 않는다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "looktalk.sms.outbound-provider=SOLAPI",
        "looktalk.sms.solapi.api-key=test-key",
        "looktalk.sms.solapi.api-secret=test-secret",
        "looktalk.sms.solapi.otp-sender-number=0212345678"
})
class SmsProviderSolapiSelectionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void solapiProviderIsSelectedAndNoBeanConflictOccurs() {
        SmsSender smsSender = applicationContext.getBean(SmsSender.class);
        SmsMessageSender smsMessageSender = applicationContext.getBean(SmsMessageSender.class);

        assertInstanceOf(SolapiSmsSender.class, smsSender);
        assertInstanceOf(SolapiSmsMessageSender.class, smsMessageSender);
        assertEquals(1, applicationContext.getBeansOfType(SolapiClient.class).size());
    }
}
