package com.looktalk.backend.sms.solapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// 실제 SOLAPI 네트워크를 호출하지 않고, request/response 직렬화·역직렬화가 공식 문서에서 확인된 스키마와
// 일치하는지 검증한다(출처: 이번 세션 solapi.com/developers/api/messages, messages-lms, messages-sms
// 세 페이지를 직접 fetch해 확인한 실제 예시 JSON).
class SolapiSendMessageJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requestSerializesToMessagesArrayNotSingularMessageObject() throws Exception {
        SolapiSendMessageRequest request = SolapiSendMessageRequest.of("0212345678", "01012345678", "테스트");

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(request));

        assertTrue(json.has("messages"), "공식 스키마는 최상위가 'messages' 배열이다.");
        assertTrue(json.get("messages").isArray());
        assertEquals(1, json.get("messages").size());
        assertFalse(json.has("message"), "'message'(단수) 필드를 써서는 안 된다.");

        JsonNode item = json.get("messages").get(0);
        assertEquals("01012345678", item.get("to").asText());
        assertEquals("0212345678", item.get("from").asText());
        assertEquals("테스트", item.get("text").asText());
        assertTrue(item.get("autoTypeDetect").asBoolean(), "긴 메시지가 임의로 잘리지 않도록 autoTypeDetect=true여야 한다.");

        // showMessageList는 최상위 필드다(공식 문서 solapi.com/developers/api/messages 직접 fetch로 확인:
        // "Body Params 섹션의 최상위 레벨"). true가 아니면 성공해도 messageList가 응답에서 빠져 messageId를
        // 못 읽는다.
        assertTrue(json.has("showMessageList"), "showMessageList는 최상위 필드여야 한다.");
        assertTrue(json.get("showMessageList").asBoolean(), "showMessageList=true여야 messageList를 받을 수 있다.");
    }

    @Test
    void successResponseWithMessageListParsesMessageId() throws Exception {
        String json = """
                {
                  "groupInfo": {"groupId": "G4V2024", "accountId": "123", "status": "SENDING"},
                  "failedMessageList": [],
                  "messageList": [
                    {"messageId": "M4V2024ABC", "statusCode": "2000"}
                  ]
                }
                """;

        SolapiSendMessageResponse response = MAPPER.readValue(json, SolapiSendMessageResponse.class);

        assertFalse(response.isFailure());
        SolapiSendMessageResponse.MessageResult result = response.singleResultOrNull();
        assertEquals("M4V2024ABC", result.messageId());
        assertEquals("2000", result.statusCode());
    }

    @Test
    void failureResponseWithOnlyFailedMessageListIsDetectedAsFailure() throws Exception {
        String json = """
                {
                  "groupInfo": {"groupId": "G4V2024", "accountId": "123", "status": "SENDING"},
                  "failedMessageList": [
                    {"messageId": "M4V2024FAIL", "statusCode": "3040", "statusMessage": "등록되지 않은 발신번호입니다."}
                  ],
                  "messageList": []
                }
                """;

        SolapiSendMessageResponse response = MAPPER.readValue(json, SolapiSendMessageResponse.class);

        assertTrue(response.isFailure());
        SolapiSendMessageResponse.MessageResult result = response.singleResultOrNull();
        assertEquals("M4V2024FAIL", result.messageId());
        assertEquals("3040", result.statusCode());
    }

    @Test
    void emptyResponseHasNoSingleResult() throws Exception {
        String json = "{\"groupInfo\": {\"groupId\": \"G\", \"accountId\": \"1\", \"status\": \"SENDING\"}, "
                + "\"failedMessageList\": [], \"messageList\": []}";

        SolapiSendMessageResponse response = MAPPER.readValue(json, SolapiSendMessageResponse.class);

        assertNull(response.singleResultOrNull());
    }
}
