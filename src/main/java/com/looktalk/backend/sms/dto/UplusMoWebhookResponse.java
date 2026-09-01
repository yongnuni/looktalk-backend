package com.looktalk.backend.sms.dto;

// LG U+ 메시지허브 MO 공식 webhook 응답 사양(조사에서 확인된 형태).
// 성공: {"code":"10000","message":"success"} / 일시적 처리 실패(재시도 유도): code>=20000.
// 공식 문서에서 code의 타입이 string인지 number인지 별도로 확정하지 못해, 조사에서 확인된 예시 그대로
// string으로 구현한다(최종 보고 참고).
public record UplusMoWebhookResponse(String code, String message) {

    private static final String SUCCESS_CODE = "10000";
    private static final String TRANSIENT_FAILURE_CODE = "20000";

    public static UplusMoWebhookResponse success() {
        return new UplusMoWebhookResponse(SUCCESS_CODE, "success");
    }

    // 영구 실패(unknown number/friend, E2EE key 없음)에도 U+ 재시도를 유발하지 않기 위해 이 서비스 관점에서는
    // "success"로 응답한다(§24/§25) - 별도의 실패 코드를 쓰지 않는다. 이 메서드는 DB 장애 등 일시적 내부
    // 오류에서만 사용한다.
    public static UplusMoWebhookResponse transientFailure() {
        return new UplusMoWebhookResponse(TRANSIENT_FAILURE_CODE, "internal error, please retry");
    }
}
