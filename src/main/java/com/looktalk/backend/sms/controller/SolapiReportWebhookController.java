package com.looktalk.backend.sms.controller;

import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.sms.dto.SolapiSingleReportWebhookRequest;
import com.looktalk.backend.sms.dto.SolapiWebhookAckResponse;
import com.looktalk.backend.sms.service.SmsDeliveryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md §57.2 발송결과 흐름을 실제 SOLAPI SINGLE-REPORT webhook으로 연결한다.
// SecurityConfig에서 이 endpoint만 permitAll이고, 실제 인증은 X-Solapi-Secret 검증으로 한다. GROUP-REPORT는
// 이 handler에서 처리하지 않는다.
//
// 출처(이번 세션 solapi.com/developers/api/webhook 직접 fetch, 2회 독립 확인):
// - "모든 웹훅 데이터는 JSON 배열 형태로 전달됩니다" - SINGLE-REPORT body는 object가 아니라 array다.
// - X-Solapi-Secret 헤더는 등록한 secret 원문이 아니라 "webhook 등록 시 설정한 secret 문자열을 SHA1으로
//   해시"한 hex 문자열이다(Node.js 예시: crypto.createHash('sha1').update(secret).digest('hex')).
//   그래서 raw secret과 header 값을 직접 비교하지 않고, 우리가 설정한 secret을 SHA1으로 해시한 뒤 비교한다.
@RestController
@RequestMapping("/api/integrations/sms/reports/solapi")
public class SolapiReportWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SolapiReportWebhookController.class);

    private static final String EVENT_NAME_HEADER = "X-Solapi-Event-Name";
    private static final String SECRET_HEADER = "X-Solapi-Secret";
    private static final String SINGLE_REPORT_EVENT = "SINGLE-REPORT";
    private static final String SHA1_ALGORITHM = "SHA-1";

    private final SmsDeliveryService smsDeliveryService;
    private final String webhookSecret;

    public SolapiReportWebhookController(
            SmsDeliveryService smsDeliveryService,
            @Value("${looktalk.sms.solapi.report-webhook-secret:}") String webhookSecret
    ) {
        this.smsDeliveryService = smsDeliveryService;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    public ApiResponse<SolapiWebhookAckResponse> handleReport(
            @RequestHeader(name = EVENT_NAME_HEADER, required = false) String eventName,
            @RequestHeader(name = SECRET_HEADER, required = false) String secret,
            @RequestBody List<SolapiSingleReportWebhookRequest> reports
    ) {
        requireValidSecret(secret);

        if (!SINGLE_REPORT_EVENT.equals(eventName)) {
            // GROUP-REPORT 등 이 handler가 처리하지 않는 이벤트를 잘못 처리하지 않는다. 재시도를 유발할 이유가
            // 없으므로 그대로 성공으로 ack한다.
            return ApiResponse.success(SolapiWebhookAckResponse.ok());
        }

        // 배열 안 한 건이 이미 idempotently 처리된 경우(UNKNOWN_MESSAGE_ID 포함) 다른 건 처리까지 실패시키지
        // 않는다. 예상하지 못한 예외(DB 장애 등)만 그대로 던져 요청 전체를 실패시키고 SOLAPI의 자동 재시도
        // (최초 1회 + 최대 7회, 지수 백오프)를 유도한다 - 이미 반영된 건은 applyReport의 ALREADY_FINAL 처리로
        // 재시도돼도 안전하다.
        for (SolapiSingleReportWebhookRequest report : reports) {
            if (report.messageId() == null || report.messageId().isBlank()) {
                log.warn("SINGLE-REPORT 항목에 messageId가 없어 건너뜁니다.");
                continue;
            }

            SmsDeliveryService.ReportApplyResult result = smsDeliveryService.applyReport(
                    report.messageId(), report.statusCode(), report.statusMessage(), report.dateReceived());

            if (result == SmsDeliveryService.ReportApplyResult.UNKNOWN_MESSAGE_ID) {
                log.warn("SINGLE-REPORT의 provider messageId를 찾을 수 없어 건너뜁니다.");
            }
        }

        return ApiResponse.success(SolapiWebhookAckResponse.ok());
    }

    private void requireValidSecret(String secret) {
        // report-webhook-secret이 비어 있으면(운영자가 아직 설정하지 않음) 이 webhook을 무조건 거부한다 -
        // secret 미설정 상태로 열어두지 않는다.
        if (webhookSecret.isBlank() || secret == null || !constantTimeEquals(secret, sha1Hex(webhookSecret))) {
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
    }

    private String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA1_ALGORITHM);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
