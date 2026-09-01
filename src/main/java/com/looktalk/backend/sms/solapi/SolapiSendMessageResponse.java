package com.looktalk.backend.sms.solapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// SOLAPI POST /messages/v4/send-many/detail 응답(출처: 이번 세션 solapi.com/developers/api/messages 공식
// 페이지를 직접 fetch해 확인한 실제 예시 JSON):
// { "groupInfo": {...}, "failedMessageList": [{messageId,statusCode,statusMessage}],
//   "messageList": [{messageId,statusCode}] }
// messageList는 접수에 성공한 건, failedMessageList는 접수 단계에서 즉시 거부된 건이다. Look Talk은 배열에
// 항상 1건만 보내므로 두 리스트를 합쳐 최대 1건만 존재한다고 가정한다. 알 수 없는 필드는 무시한다.
@JsonIgnoreProperties(ignoreUnknown = true)
record SolapiSendMessageResponse(
        GroupInfo groupInfo,
        List<MessageResult> failedMessageList,
        List<MessageResult> messageList
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroupInfo(String groupId, String accountId, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageResult(String messageId, String statusCode, String statusMessage) {
    }

    // 성공(messageList) 또는 실패(failedMessageList) 목록에서 첫 항목을 꺼낸다. 두 목록이 모두 비어 있으면
    // 예상하지 못한 응답이다(빈 배열 send는 이 클라이언트가 만들지 않으므로 도달하지 않아야 한다).
    MessageResult singleResultOrNull() {
        if (messageList != null && !messageList.isEmpty()) {
            return messageList.get(0);
        }
        if (failedMessageList != null && !failedMessageList.isEmpty()) {
            return failedMessageList.get(0);
        }
        return null;
    }

    boolean isFailure() {
        return messageList == null || messageList.isEmpty();
    }
}
