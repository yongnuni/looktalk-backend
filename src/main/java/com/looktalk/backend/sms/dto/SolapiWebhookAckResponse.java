package com.looktalk.backend.sms.dto;

// SOLAPI webhook에 대한 이 서비스의 응답. SOLAPI는 webhook 성공 응답 형식을 엄격히 강제하지 않으므로(공식
// 문서에서 U+ MO처럼 고정된 성공 body 스펙을 확인하지 못함) 2xx 상태 코드만으로 처리 완료를 알린다.
public record SolapiWebhookAckResponse(String status) {

    public static SolapiWebhookAckResponse ok() {
        return new SolapiWebhookAckResponse("OK");
    }
}
