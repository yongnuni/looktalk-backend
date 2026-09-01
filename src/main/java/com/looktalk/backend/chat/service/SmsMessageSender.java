package com.looktalk.backend.chat.service;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS
// 인증번호 전용 com.looktalk.backend.user.service.SmsSender(sendVerificationCode)와는 목적이 다른,
// 임의 문자열을 외부 SMS 상대에게 보내는 범용 발송 abstraction이다. 인증 도메인과 Chat을 억지로 결합하지 않는다.
// from을 요구하는 이유: 친구채팅은 환자별로 배정된 SMS 회신 번호(SmsInboundNumberAssignment)를 발신번호로
// 써야 친구가 답장했을 때 같은 번호로 되돌아올 수 있다(OTP용 고정 발신번호와는 다르다).
public interface SmsMessageSender {

    // Provider가 확정되어 실제 발송이 가능한 상태인지 여부.
    boolean isConfigured();

    // 발송 실패 시 SmsSendFailedException을 던진다. isConfigured()가 false일 때는 호출하지 않는다.
    // 성공 시 Provider가 부여한 messageId를 담아 반환한다(delivery report 연동에 사용).
    SmsSendResult send(SmsOutboundMessage message);
}
