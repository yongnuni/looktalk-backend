package com.looktalk.backend.sms.solapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// SOLAPI POST /messages/v4/send-many/detail 요청 body(출처: 이번 세션 solapi.com/developers/api/messages,
// solapi.com/developers/api/messages-lms, solapi.com/developers/api/messages-sms 세 개의 공식 페이지를 직접
// fetch해 교차 확인 - 세 페이지 모두 최상위가 "message"(단수 객체)가 아니라 "messages"(배열)임을 일관되게
// 보여준다). Look Talk은 항상 배열에 1건만 담아 보낸다(공식 문서상 send-many 계열 endpoint는 단일 건 발송에도
// 사용 가능하다고 명시됨).
// autoTypeDetect=true로 두면 text가 90byte를 초과하거나 subject가 있을 때 SOLAPI가 자동으로 SMS -> LMS로
// 전환해 발송한다. 긴 채팅 내용을 임의로 90byte에서 자르지 않기 위해 이 옵션을 쓴다.
// showMessageList: 최상위 필드(공식 문서 solapi.com/developers/api/messages 직접 fetch로 확인 - "Body
// Params 섹션의 최상위 레벨"에 위치하는 Boolean이며, "showMessageList: true일 때만" 응답에 messageList가
// 포함된다). Look Talk은 성공 시 messageList[0].messageId를 반드시 읽어야 하므로 항상 true로 보낸다 - 이
// 값을 빠뜨리면 정상 접수된 발송도 messageId를 못 찾아 잘못 실패 처리된다.
record SolapiSendMessageRequest(List<MessageBody> messages, @JsonProperty("showMessageList") boolean showMessageList) {

    record MessageBody(
            String to,
            String from,
            String text,
            @JsonProperty("autoTypeDetect") boolean autoTypeDetect
    ) {
    }

    static SolapiSendMessageRequest of(String from, String to, String text) {
        return new SolapiSendMessageRequest(List.of(new MessageBody(to, from, text, true)), true);
    }
}
