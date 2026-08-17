package com.looktalk.backend.global.exception;

import org.springframework.http.HttpStatus;

// docs/LookTalk_Backend_API_Spec_v2.1.md 15. 공통 Error Code 표를 그대로 반영한다.
// 표에 없는 Error Code는 임의로 추가하지 않는다.
public enum ErrorCode {

    COMMON_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요."),
    COMMON_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증 정보가 존재하지 않는다"),
    COMMON_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access Token이 유효하지 않는다"),
    COMMON_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access Token이 만료된다"),
    COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "API 접근 권한이 없다"),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API 또는 리소스를 찾을 수 없습니다."),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리하지 못한 서버 오류가 발생한다"),

    AUTH_DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "loginId가 이미 존재한다"),
    AUTH_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이메일이 이미 가입에 사용된다"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않는다"),
    AUTH_INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않는다"),
    AUTH_VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료된다"),
    AUTH_EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증이 완료되지 않는다"),
    AUTH_INVALID_INVITE_CODE(HttpStatus.BAD_REQUEST, "병원 초대코드가 유효하지 않는다"),
    AUTH_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "비밀번호 재설정 토큰이 유효하지 않는다"),
    AUTH_RESET_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "비밀번호 재설정 토큰이 만료된다"),
    AUTH_REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "Refresh Token Cookie가 존재하지 않는다"),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh Token이 유효하지 않는다"),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh Token이 만료된다"),

    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "이메일 발송에 실패한다"),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자 데이터가 존재하지 않는다"),
    SMS_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "전화번호 인증이 필요한 기능을 인증 전에 호출한다"),

    STAFF_NOT_FOUND(HttpStatus.NOT_FOUND, "의료진 데이터가 존재하지 않는다"),
    STAFF_PATIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "대상 환자가 존재하지 않는다"),
    STAFF_PATIENT_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "동일 담당 관계가 이미 존재한다"),
    STAFF_PATIENT_NOT_ASSIGNED(HttpStatus.NOT_FOUND, "담당 관계가 존재하지 않는다"),

    E2EE_KEY_REQUIRED(HttpStatus.CONFLICT, "현재 사용자 E2EE 공개키가 등록되지 않는다"),
    E2EE_TARGET_KEY_REQUIRED(HttpStatus.CONFLICT, "상대 사용자 E2EE 공개키가 등록되지 않는다"),
    E2EE_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "지정한 E2EE 공개키가 존재하지 않는다"),
    E2EE_INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "암호화 Payload 형식이 올바르지 않는다"),

    MEMO_NOT_FOUND(HttpStatus.NOT_FOUND, "메모가 존재하지 않는다"),

    PHRASE_NOT_FOUND(HttpStatus.NOT_FOUND, "자주 쓰는 문장이 존재하지 않는다"),

    FRIEND_NOT_FOUND(HttpStatus.NOT_FOUND, "지인 데이터가 존재하지 않는다"),
    FRIEND_ALREADY_EXISTS(HttpStatus.CONFLICT, "같은 전화번호가 이미 등록된다"),

    CHAT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "선택한 내부 채팅 상대가 존재하지 않는다"),
    CHAT_SELF_TARGET(HttpStatus.BAD_REQUEST, "자기 자신을 채팅 상대방으로 지정한다"),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방이 존재하지 않는다"),
    CHAT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 채팅방의 participant가 아니다"),

    SMS_PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "SMS Provider가 설정되지 않는다"),
    SMS_SEND_FAILED(HttpStatus.BAD_GATEWAY, "SMS Provider 전송에 실패한다"),

    EMERGENCY_CALL_NOT_FOUND(HttpStatus.NOT_FOUND, "긴급 호출이 존재하지 않는다"),

    CALIBRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Calibration이 존재하지 않는다"),
    CALIBRATION_INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, "calibrationData 형식이 올바르지 않는다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
