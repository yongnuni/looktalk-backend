package com.looktalk.backend.sms.entity;

// U+ MO inbound 처리 결과. 여기 기록되는 상태는 전부 "영구적으로 재시도해도 달라지지 않는" 결과만 포함한다.
// DB 장애 등 일시적 내부 오류는 receipt 자체를 남기지 않고 U+에 재시도를 유도하는 실패 응답을 돌려준다
// (SmsInboundMessageService).
public enum SmsInboundProcessingStatus {
    PROCESSED,
    REJECTED_UNKNOWN_NUMBER,
    REJECTED_UNKNOWN_FRIEND,
    REJECTED_NO_E2EE_KEY
}
