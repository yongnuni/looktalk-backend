package com.looktalk.backend.sms.solapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// SOLAPI REST API(https://api.solapi.com) 저수준 client. Spring Bean으로 한 번만 생성해 재사용한다
// (요청마다 새로 만들지 않는다). SOLAPI-specific 타입(이 클래스, 요청/응답 DTO)은 이 패키지(sms.solapi) 밖으로
// 노출하지 않는다 - UserService/ChatMessageService는 SolapiClient를 직접 알지 못하고, SolapiSmsSender/
// SolapiSmsMessageSender(domain interface 구현체)를 통해서만 간접적으로 사용한다.
//
// 인증: SOLAPI HMAC-SHA256 API Key 인증(공식 문서 developers.solapi.dev/references/authentication/api-key
// 기준). Authorization: HMAC-SHA256 apiKey={key}, date={ISO8601 UTC}, salt={random}, signature={hex}
// signature = hex(HMAC-SHA256(key=apiSecret, message=date+salt)).
//
// 공식 REST API 대신 net.nurigo:sdk(SOLAPI 공식 Java SDK)를 쓰는 방법도 있었으나, 이 프로젝트의
// Spring Boot 4.1.0/Java 17 toolchain과의 의존성 호환성(전이 의존성 버전 충돌 등)을 이번 세션에서 검증할 수
// 없어 REST API를 직접 호출하는 이 adapter를 선택했다(최종 보고 참고).
@Component
@ConditionalOnProperty(prefix = "looktalk.sms", name = "outbound-provider", havingValue = "SOLAPI")
public class SolapiClient {

    private static final Logger log = LoggerFactory.getLogger(SolapiClient.class);
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://api.solapi.com";
    // 출처: 이번 세션 solapi.com/developers/api/messages(공식, 직접 fetch)에서 확인된 request(messages 배열)/
    // response(groupInfo+messageList+failedMessageList) 구조가 이 endpoint 기준으로 서로 일치했다.
    // 검색 스니펫에서만 언급된 "/messages/v4/send"(단일 메시지 전용)의 정확한 response 구조는 이 세션에서
    // 직접 fetch로 재검증하지 못해(docs.solapi.com 접근 불가), request/response가 실제로 매칭 확인된 이
    // endpoint를 사용한다. Look Talk은 항상 1건만 보내므로 bulk endpoint를 그대로 단건 발송에 재사용한다.
    private static final String SEND_MESSAGE_PATH = "/messages/v4/send-many/detail";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final RestClient restClient;
    private final String apiKey;
    private final String apiSecret;

    // RestClient.Builder를 Spring에 위임하지 않고 직접 만든다 - 이 프로젝트의 starter 구성(spring-boot-
    // starter-webmvc)에서 RestClientAutoConfiguration이 RestClient.Builder Bean을 자동 등록해주지 않기
    // 때문이다(실제 확인됨: 없으면 SolapiClient Bean 생성이 UnsatisfiedDependencyException으로 실패한다).
    // 생성자가 2개(운영용/테스트용)라 Spring이 자동으로 하나를 고르지 못한다 - @Autowired로 운영 경로를
    // 명시한다.
    @Autowired
    public SolapiClient(
            @Value("${looktalk.sms.solapi.api-key:}") String apiKey,
            @Value("${looktalk.sms.solapi.api-secret:}") String apiSecret
    ) {
        this(RestClient.builder().baseUrl(BASE_URL), apiKey, apiSecret);
    }

    // package-private(테스트 전용) - MockRestServiceServer.bindTo(RestClient.Builder)로 실제 HTTP 응답(상태
    // 코드/body)을 흉내내 SolapiClientTest에서 에러 처리 경로를 검증하기 위한 생성자다. 운영 코드 경로(위
    // public 생성자)는 항상 이 생성자로 위임하므로 실제 동작은 동일하다.
    SolapiClient(RestClient.Builder restClientBuilder, String apiKey, String apiSecret) {
        // outbound-provider=SOLAPI로 선택되었는데 실제 인증에 필요한 값이 비어 있으면 애플리케이션 기동
        // 단계에서 즉시 실패시킨다(SmtpEmailSender와 동일한 fail-fast 원칙).
        Assert.hasText(apiKey,
                "looktalk.sms.outbound-provider=SOLAPI인데 looktalk.sms.solapi.api-key(SOLAPI_API_KEY)가 설정되지 않았습니다.");
        Assert.hasText(apiSecret,
                "looktalk.sms.outbound-provider=SOLAPI인데 looktalk.sms.solapi.api-secret(SOLAPI_API_SECRET)이 설정되지 않았습니다.");

        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.restClient = restClientBuilder.build();
    }

    // 단일 메시지 발송(messages 배열에 1건만 담는다). 실패 시(네트워크 오류, 4xx/5xx, messageList가 비어
    // 있는 응답) SolapiApiException을 던진다. 이 호출은 caller(SolapiSmsSender/SolapiSmsMessageSender)가
    // DB write transaction 밖에서 수행해야 한다.
    public SolapiSendMessageResponse.MessageResult sendMessage(String from, String to, String text) {
        SolapiSendMessageResponse response;
        try {
            response = restClient.post()
                    .uri(SEND_MESSAGE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, buildAuthorizationHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SolapiSendMessageRequest.of(from, to, text))
                    .retrieve()
                    .body(SolapiSendMessageResponse.class);
        } catch (RestClientResponseException exception) {
            // SOLAPI가 HTTP 레벨에서 요청 자체를 거부한 경우(인증 실패, 잘못된 요청 형식 등) - status와 응답
            // body를 여기서만 볼 수 있으므로 감싸서 던지기 전에 남긴다. API key/secret/Authorization 헤더,
            // 전화번호(to/from), 메시지 본문(text)은 절대 로그에 남기지 않는다 - 아래서 남기는 것은 SOLAPI가
            // 스스로 반환한 errorCode/message뿐이다.
            logHttpFailure(exception);
            throw new SolapiApiException("SOLAPI 메시지 발송 API 호출에 실패했습니다.", exception);
        } catch (RestClientException exception) {
            // HTTP 응답 자체를 받지 못한 경우(네트워크 오류, timeout 등) - status/body가 없으므로 예외
            // class/message만 남긴다.
            log.error("SOLAPI 메시지 발송 API 호출 중 네트워크 오류가 발생했습니다. endpoint={}, exceptionClass={}, exceptionMessage={}",
                    SEND_MESSAGE_PATH, exception.getClass().getSimpleName(), exception.getMessage());
            throw new SolapiApiException("SOLAPI 메시지 발송 API 호출에 실패했습니다.", exception);
        }

        SolapiSendMessageResponse.MessageResult result = response == null ? null : response.singleResultOrNull();
        if (result == null || result.messageId() == null || result.messageId().isBlank()) {
            throw new SolapiApiException("SOLAPI 메시지 발송 응답에 messageId가 없습니다.");
        }
        if (response.isFailure()) {
            // HTTP 200이지만 failedMessageList에만 있는 경우 - 메시지 자체(발신/수신번호 등)가 SOLAPI 검증에서
            // 거부된 것이다. statusCode/statusMessage는 SOLAPI가 이 message 건에 대해 직접 반환한 구조화된
            // 필드이므로(전화번호/본문 원문이 아니다) 로그에 남겨도 안전하다.
            log.error("SOLAPI 메시지 접수가 거부되었습니다. endpoint={}, statusCode={}, statusMessage={}",
                    SEND_MESSAGE_PATH, result.statusCode(), result.statusMessage());
            throw new SolapiApiException(
                    "SOLAPI 메시지 접수가 거부되었습니다: statusCode=" + result.statusCode()
                            + ", statusMessage=" + result.statusMessage());
        }
        return result;
    }

    // HTTP status(예: 401/400)와 SOLAPI가 반환한 error body(보통 {"errorCode":"...","message":"..."} 형태)를
    // 남긴다. body 파싱에 실패하면(형식이 다른 경우) status만 남기고 원본 body는 남기지 않는다 - 예상 밖의
    // 응답 형식에 전화번호/본문이 echo되어 있을 가능성을 배제할 수 없기 때문이다.
    private void logHttpFailure(RestClientResponseException exception) {
        SolapiErrorBody errorBody = parseErrorBody(exception.getResponseBodyAsString());
        log.error(
                "SOLAPI 메시지 발송 요청이 HTTP 레벨에서 거부되었습니다. endpoint={}, httpStatus={}, errorCode={}, errorMessage={}",
                SEND_MESSAGE_PATH,
                exception.getStatusCode().value(),
                errorBody == null ? null : errorBody.errorCode(),
                errorBody == null ? null : errorBody.message()
        );
    }

    private SolapiErrorBody parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return ERROR_BODY_MAPPER.readValue(body, SolapiErrorBody.class);
        } catch (Exception parseException) {
            return null;
        }
    }

    // package-private(테스트 가시성) - HMAC-SHA256 서명이 공식 스펙(date+salt를 apiSecret으로 서명)대로 계산
    // 되는지 SolapiClientTest에서 직접 검증한다.
    String buildAuthorizationHeader() {
        String date = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String salt = UUID.randomUUID().toString().replace("-", "");
        String signature = sign(date + salt);

        return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
                .formatted(apiKey, date, salt, signature);
    }

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SOLAPI HMAC-SHA256 서명 생성에 실패했습니다.", exception);
        }
    }
}
