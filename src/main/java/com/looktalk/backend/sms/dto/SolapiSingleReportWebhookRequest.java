package com.looktalk.backend.sms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// SOLAPI SINGLE-REPORT webhook payload(출처: solapi.com/developers/api/webhook, 공식 직접 fetch로 확인).
// groupId/type/to/from/networkCode/customFields 등 이 조사에서 확인된 다른 필드는 이 기능에서 쓰이지 않으므로
// 매핑하지 않는다(알 수 없는 필드는 무시).
// dateProcessed="처리 완료 시각", dateReported="리포트 수신 시각", dateReceived="단말 수신 시각" - 셋 다 공식
// 문서에 정의된 의미가 다르다. deliveredAt에는 dateReceived만 사용한다(SmsDeliveryService.applyReport).
// dateProcessed는 이 기능에서 쓰지 않으므로 매핑하지 않는다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SolapiSingleReportWebhookRequest(
        String messageId,
        String statusCode,
        String statusMessage,
        String dateReported,
        String dateReceived
) {
}
