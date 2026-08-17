package com.looktalk.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.auth.service.EmailSender;
import com.looktalk.backend.auth.service.LoggingEmailSender;
import com.looktalk.backend.auth.service.SmtpEmailSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 8, 11. 이메일 실발송
// looktalk.mail.provider property로 기본값(logging)에서는 LoggingEmailSender가 선택되고, EmailSender Bean이
// 정확히 1개만 등록되는지(NoUniqueBeanDefinitionException 없음) 실제 컨텍스트로 검증한다. smtp provider 선택은
// EmailProviderSmtpSelectionTest에서 별도 property로 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailProviderSelectionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void loggingProviderIsSelectedByDefault() {
        // getBean(EmailSender.class)가 예외 없이 정확히 1개를 반환하는 것 자체가
        // NoUniqueBeanDefinitionException이 없다는 증거다.
        EmailSender emailSender = applicationContext.getBean(EmailSender.class);

        assertInstanceOf(LoggingEmailSender.class, emailSender);
        assertTrue(applicationContext.getBeansOfType(SmtpEmailSender.class).isEmpty(),
                "기본 provider(logging)에서는 SmtpEmailSender가 등록되면 안 된다.");
    }

    // AUTH-001 regression - SMTP 도입이 기존(logging) 경로의 성공 응답을 바꾸지 않아야 한다.
    @Test
    void sendEmailVerificationStillSucceedsWithDefaultLoggingProvider() throws Exception {
        String body = "{\"email\":\"eps-" + System.nanoTime() + "@example.com\",\"purpose\":\"SIGNUP\"}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/auth/email-verifications"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertTrue(json.get("success").asBoolean());
        assertTrue(json.get("data").get("expiresInSeconds").asLong() > 0);
    }
}
