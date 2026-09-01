package com.looktalk.backend.sms.controller;

import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.sms.dto.UplusMoWebhookRequest;
import com.looktalk.backend.sms.dto.UplusMoWebhookResponse;
import com.looktalk.backend.sms.service.SmsInboundMessageService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// LG U+ 메시지허브 MO webhook 수신 endpoint(이번 조사 §2/§19~§22). 공식 문서에서 U+가 서명/인증 헤더를
// 제공하는지 이 세션에서 확인하지 못했다(openapi.sms.uplus.co.kr 접근 불가) - 존재하지 않는 서명 방식을
// 만들어내지 않고, 대신 URL 경로에 고엔트로피 token을 두는 방식으로 무인증 상태를 피한다(§21). token은
// looktalk.sms.uplus.webhook-token(UPLUS_WEBHOOK_TOKEN)으로 관리하며 U+ 콘솔에 등록하는 webhook URL 자체에
// 이 token을 포함시킨다. 추가로 moNumber가 실제 배정된 서비스 번호인지(SmsInboundMessageService 내부), DTO
// validation, moKey idempotency가 각각 방어 계층으로 동작한다. rate limiting은 이번 범위에서 구현하지 않았다
// (최종 보고에 한계로 명시한다).
@RestController
@RequestMapping("/api/integrations/sms/inbound/uplus")
public class UplusMoInboundController {

    private static final Logger log = LoggerFactory.getLogger(UplusMoInboundController.class);

    private final SmsInboundMessageService inboundMessageService;
    private final String webhookToken;

    public UplusMoInboundController(
            SmsInboundMessageService inboundMessageService,
            @Value("${looktalk.sms.uplus.webhook-token:}") String webhookToken
    ) {
        this.inboundMessageService = inboundMessageService;
        this.webhookToken = webhookToken;
    }

    @PostMapping("/{token}")
    public UplusMoWebhookResponse handleInbound(
            @PathVariable String token,
            @Valid @RequestBody UplusMoWebhookRequest request
    ) {
        requireValidToken(token);

        boolean anyTransientFailure = false;
        for (UplusMoWebhookRequest.MoItem item : request.moLst()) {
            try {
                inboundMessageService.processMoItem(item);
            } catch (RuntimeException exception) {
                // 알 수 없는 개별 오류는 일시적 내부 실패로 간주해 U+ 재시도를 유도한다. moKey/전화번호/SMS 본문은
                // 로그에 남기지 않는다.
                log.error("U+ MO inbound 처리 중 예상하지 못한 오류가 발생했습니다.", exception);
                anyTransientFailure = true;
            }
        }

        // Batch(moLst) 안에 하나라도 일시적 실패가 있으면 배치 전체를 실패로 응답해 U+가 다시 보내도록 한다.
        // 이미 처리된 item은 moKey idempotency(SmsInboundReceipt)가 재처리를 막아주므로 전체 재전송이 안전하다.
        // "부분 성공"을 표현하는 공식 사양을 이 세션에서 확인하지 못했으므로 이 안전한 정책을 선택했다(§25).
        return anyTransientFailure ? UplusMoWebhookResponse.transientFailure() : UplusMoWebhookResponse.success();
    }

    private void requireValidToken(String token) {
        if (webhookToken.isBlank() || token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), webhookToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED);
        }
    }
}
