package com.looktalk.backend.chat.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS
// SmsMessageSender.send()의 value object. OTP(SmsSender)와 달리 chat SMS는 환자별로 발신번호(from)가 다르므로
// (환자에게 배정된 SmsInboundNumberAssignment.serviceNumber) from/to/content를 함께 넘긴다. Provider 이름을
// 여기 넣지 않는다 - 이 타입은 Provider-agnostic domain 계약이다.
public record SmsOutboundMessage(String from, String to, String content) {
}
