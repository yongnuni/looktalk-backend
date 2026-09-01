package com.looktalk.backend.sms.solapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 이번 조사(전화번호 인증 SMS 발송 실패 원인 추적) - SOLAPI가 실제로 반환한 HTTP status/errorCode/message가
// SolapiClient 밖으로 유실되지 않고 서버 로그에 남는지, 그리고 그 과정에서 API key/secret/Authorization
// header/전화번호/메시지 본문이 절대 로그에 노출되지 않는지를 실제 HTTP 응답(MockRestServiceServer)으로
// 검증한다.
class SolapiClientErrorLoggingTest {

    private static final String API_KEY = "test-api-key-should-not-appear-in-logs";
    private static final String API_SECRET = "test-api-secret-should-not-appear-in-logs";
    private static final String FROM = "01011112222";
    private static final String TO = "01033334444";
    private static final String TEXT = "should-not-appear-in-logs";
    private static final String SEND_URL = "https://api.solapi.com/messages/v4/send-many/detail";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(SolapiClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void httpLevelRejectionLogsStatusAndErrorCodeButNeverSecretsOrMessageContent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.solapi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorCode\":\"InvalidApiKey\",\"message\":\"등록되지 않은 API Key입니다.\"}"));

        SolapiClient client = new SolapiClient(builder, API_KEY, API_SECRET);

        assertThrows(SolapiApiException.class, () -> client.sendMessage(FROM, TO, TEXT));

        assertTrue(appender.list.size() >= 1, "실패 로그가 최소 1건 남아야 한다.");
        boolean sawStatusAndErrorCode = false;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertNoSecretsOrPayload(message);
            if (message.contains("401") && message.contains("InvalidApiKey")) {
                sawStatusAndErrorCode = true;
            }
        }
        assertTrue(sawStatusAndErrorCode,
                "HTTP status(401)와 SOLAPI errorCode(InvalidApiKey)가 로그에 남아야 한다: " + appender.list);
    }

    @Test
    void messageLevelRejectionLogsStatusCodeAndStatusMessage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.solapi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "groupInfo": {"groupId": "G1", "accountId": "A1", "status": "SENDING"},
                          "messageList": [],
                          "failedMessageList": [
                            {"messageId": "M1", "statusCode": "3018", "statusMessage": "등록되지 않은 발신번호입니다."}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SolapiClient client = new SolapiClient(builder, API_KEY, API_SECRET);

        SolapiApiException exception = org.junit.jupiter.api.Assertions.assertThrows(
                SolapiApiException.class, () -> client.sendMessage(FROM, TO, TEXT));

        assertTrue(exception.getMessage().contains("3018"), "statusCode가 예외 메시지에 남아야 한다: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("등록되지 않은 발신번호"),
                "statusMessage(실패 원인)가 예외 메시지에 남아야 한다: " + exception.getMessage());

        for (ILoggingEvent event : appender.list) {
            assertNoSecretsOrPayload(event.getFormattedMessage());
        }
    }

    private void assertNoSecretsOrPayload(String message) {
        assertFalse(message.contains(API_KEY), "API key가 로그에 노출되었습니다: " + message);
        assertFalse(message.contains(API_SECRET), "API secret이 로그에 노출되었습니다: " + message);
        assertFalse(message.toLowerCase().contains("authorization"), "Authorization 헤더가 로그에 노출되었습니다: " + message);
        assertFalse(message.contains(FROM), "발신번호가 로그에 노출되었습니다: " + message);
        assertFalse(message.contains(TO), "수신번호가 로그에 노출되었습니다: " + message);
        assertFalse(message.contains(TEXT), "메시지 본문이 로그에 노출되었습니다: " + message);
    }
}
