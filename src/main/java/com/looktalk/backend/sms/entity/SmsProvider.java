package com.looktalk.backend.sms.entity;

// outbound(SOLAPI)와 inbound(UPLUS_MO) provider를 하나의 enum으로 공유한다. 각 테이블의 CHECK 제약이 어떤
// 값이 그 테이블에 허용되는지를 별도로 제한한다(V10).
public enum SmsProvider {
    SOLAPI,
    UPLUS_MO
}
