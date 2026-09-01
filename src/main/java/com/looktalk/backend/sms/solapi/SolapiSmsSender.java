package com.looktalk.backend.sms.solapi;

import com.looktalk.backend.chat.service.SmsSendFailedException;
import com.looktalk.backend.user.service.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

// docs/LookTalk_Backend_API_Spec_v2.1.md 28. USER-003 - 전화번호 인증번호 실발송(OTP).
// looktalk.sms.outbound-provider(SMS_OUTBOUND_PROVIDER)=SOLAPI일 때만 활성화된다.
// OTP 발신번호(looktalk.sms.solapi.otp-sender-number)는 친구채팅 발신번호(환자별 SmsInboundNumberAssignment)와
// 목적이 다르므로 별도 값으로 관리한다 - OTP는 회신을 받을 필요가 없는 고정 번호면 충분하다.
@Component
@ConditionalOnProperty(prefix = "looktalk.sms", name = "outbound-provider", havingValue = "SOLAPI")
public class SolapiSmsSender implements SmsSender {

    private final SolapiClient solapiClient;
    private final String otpSenderNumber;

    public SolapiSmsSender(
            SolapiClient solapiClient,
            @Value("${looktalk.sms.solapi.otp-sender-number:}") String otpSenderNumber
    ) {
        Assert.hasText(otpSenderNumber, "looktalk.sms.outbound-provider=SOLAPI인데 "
                + "looktalk.sms.solapi.otp-sender-number(SOLAPI_OTP_SENDER_NUMBER)가 설정되지 않았습니다.");
        this.solapiClient = solapiClient;
        this.otpSenderNumber = otpSenderNumber;
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        // OTP code, 전화번호 원문을 로그에 남기지 않는다(SolapiApiException은 원문을 message에 담지 않는다).
        try {
            solapiClient.sendMessage(otpSenderNumber, phone, buildText(code));
        } catch (SolapiApiException exception) {
            throw new SmsSendFailedException("SOLAPI OTP 발송에 실패했습니다.", exception);
        }
    }

    private String buildText(String code) {
        return "[LookTalk] 인증번호는 " + code + "입니다.";
    }
}
