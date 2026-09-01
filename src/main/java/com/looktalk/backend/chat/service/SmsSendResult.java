package com.looktalk.backend.chat.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS
// SmsMessageSender.send() 성공 결과. providerMessageId는 SOLAPI SINGLE-REPORT webhook이 발송 건을
// 역조회하는 데 사용하는 식별자다(sms_message_delivery.provider_message_id에 저장한다).
public record SmsSendResult(String providerMessageId) {
}
