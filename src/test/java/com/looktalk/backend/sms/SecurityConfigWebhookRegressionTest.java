package com.looktalk.backend.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// §31/§40 - 새 webhook permitAll 규칙을 추가한 뒤에도, 그 두 경로 이외의 기존 보호된 API는 여전히 JWT 없이
// 접근할 수 없어야 한다(SecurityConfig 회귀 방지).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigWebhookRegressionTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void protectedEndpointStillRequiresAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat-rooms"))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void staffOnlySmsAssignmentEndpointRejectsUnauthenticated() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/staff/patients/some-patient/sms-inbound-number"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"serviceNumber\":\"0212345678\"}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }
}
