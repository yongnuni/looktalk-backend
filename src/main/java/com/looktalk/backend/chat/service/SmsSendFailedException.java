package com.looktalk.backend.chat.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS - SmsMessageSender 구현체가 발송 실패를 알릴 때 사용한다.
// ChatMessageService는 이 예외만 SMS_SEND_FAILED(502)로 변환하고, 그 외 예외는 그대로 전파한다.
public class SmsSendFailedException extends RuntimeException {

    public SmsSendFailedException(String message) {
        super(message);
    }

    public SmsSendFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
