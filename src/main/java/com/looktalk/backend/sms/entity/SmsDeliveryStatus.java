package com.looktalk.backend.sms.entity;

// SOLAPI SINGLE-REPORT의 statusCode를 이 4개 상태로 매핑한다(SmsDeliveryService). "발송 요청이 accepted됨"과
// "실제 단말에 배달됨"을 구분한다 - accepted되었다는 이유만으로 DELIVERED로 표시하지 않는다.
public enum SmsDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}
