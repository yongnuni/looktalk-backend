package com.looktalk.backend.sms.solapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// SOLAPI가 HTTP 레벨에서 요청을 거부할 때(인증 실패, 잘못된 요청 형식 등) 반환하는 최상위 에러 응답 형태
// (공식 문서 solapi.com/developers/api/messages의 에러 응답 예시 기준: {"errorCode": "...", "message": "..."}).
// SolapiSendMessageResponse(성공 시 messageList/failedMessageList)와는 별개 shape이므로 분리한다. 알 수 없는
// 필드는 무시하고, errorCode/message 둘 다 이 프로젝트가 전화번호/메시지 본문을 담아 보내지 않는 provider
// 레벨 메타데이터이므로 로그에 남겨도 안전하다.
@JsonIgnoreProperties(ignoreUnknown = true)
record SolapiErrorBody(String errorCode, String message) {
}
