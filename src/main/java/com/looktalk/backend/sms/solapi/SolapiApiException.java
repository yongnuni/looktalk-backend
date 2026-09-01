package com.looktalk.backend.sms.solapi;

// SOLAPI REST API 호출(네트워크 오류, 4xx/5xx, 예상한 응답 형식이 아님) 실패를 나타낸다. 이 예외는 sms.solapi
// 패키지 밖으로 노출되지 않고, SolapiSmsSender/SolapiSmsMessageSender가 도메인 계약의
// com.looktalk.backend.chat.service.SmsSendFailedException으로 변환해 던진다.
public class SolapiApiException extends RuntimeException {

    public SolapiApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public SolapiApiException(String message) {
        super(message);
    }
}
