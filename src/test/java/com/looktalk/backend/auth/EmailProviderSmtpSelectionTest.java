package com.looktalk.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.looktalk.backend.auth.service.EmailSender;
import com.looktalk.backend.auth.service.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 8, 11. 이메일 실발송
// looktalk.mail.provider=smtp일 때 SmtpEmailSender가 선택되고, LoggingEmailSender와 동시에 등록되지 않는지
// 실제 컨텍스트로 검증한다. EmailProviderSelectionTest(기본 logging)와 별도 property 조합이라 별도 컨텍스트로
// 분리했다 - 이 계약(어떤 Bean이 실제로 등록되는가) 자체가 검증 대상이라 mock/fake로는 증명할 수 없기 때문이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "looktalk.mail.provider=smtp",
        "looktalk.mail.from=no-reply@looktalk.local",
        "spring.mail.host=smtp.example.com",
        // Bean 선택/충돌 여부만 검증하는 테스트이므로 username/password fail-fast 요구까지 얹지 않도록
        // auth를 꺼둔다(그 요구 자체는 SmtpEmailSenderTest의 단위 테스트가 별도로 검증한다).
        "spring.mail.properties.mail.smtp.auth=false"
})
class EmailProviderSmtpSelectionTest {

    @Autowired
    private ApplicationContext applicationContext;

    // 실제 SMTP 서버에 연결하지 않도록 autoconfigure된 JavaMailSender를 mock으로 치환한다.
    @MockitoBean
    private JavaMailSender javaMailSender;

    @Test
    void smtpProviderIsSelectedAndNoBeanConflictOccurs() {
        // getBean(EmailSender.class)가 예외 없이 정확히 1개를 반환하는 것 자체가
        // NoUniqueBeanDefinitionException이 없다는 증거다.
        EmailSender emailSender = applicationContext.getBean(EmailSender.class);

        assertInstanceOf(SmtpEmailSender.class, emailSender);
        assertEquals(1, applicationContext.getBeansOfType(SmtpEmailSender.class).size());
    }
}
