package com.looktalk.backend.user.support;

// docs/LookTalk_Backend_API_Spec_v2.1.md 29. USER-004 / 49. FRIEND-002 - 전화번호 정규화 규칙을 공유한다.
// 숫자만 남기고 하이픈/공백 등 구분자를 제거한다.
public final class PhoneNormalizer {

    private PhoneNormalizer() {
    }

    public static String normalize(String rawPhone) {
        return rawPhone.replaceAll("[^0-9]", "");
    }
}
