package com.looktalk.backend.sms.solapi;

import com.looktalk.backend.chat.service.SmsMessageSender;
import com.looktalk.backend.chat.service.SmsOutboundMessage;
import com.looktalk.backend.chat.service.SmsSendFailedException;
import com.looktalk.backend.chat.service.SmsSendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 57.2 CHAT-006 SMS - 친구채팅 실발송.
// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER)=SOLAPI일 때만 활성화된다. from은 환자별로 배정된
// SmsInboundNumberAssignment.serviceNumber를 호출부(ChatMessageService)가 결정해 넘긴다 - 이 클래스는 어떤
// 번호를 from으로 쓸지 스스로 결정하지 않는다.
@Component
@ConditionalOnProperty(prefix = "looktalk.sms", name = "outbound-provider", havingValue = "SOLAPI")
public class SolapiSmsMessageSender implements SmsMessageSender {

    private final SolapiClient solapiClient;

    public SolapiSmsMessageSender(SolapiClient solapiClient) {
        this.solapiClient = solapiClient;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public SmsSendResult send(SmsOutboundMessage message) {
        try {
            SolapiSendMessageResponse.MessageResult result =
                    solapiClient.sendMessage(message.from(), message.to(), message.content());
            return new SmsSendResult(result.messageId());
        } catch (SolapiApiException exception) {
            throw new SmsSendFailedException("SOLAPI 친구채팅 SMS 발송에 실패했습니다.", exception);
        }
    }
}
