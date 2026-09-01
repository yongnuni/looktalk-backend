package com.looktalk.backend.sms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// docs/LookTalk_Backend_Sms_Investigation.md(§2/§20) 조사에서 확인된 LG U+ 메시지허브 MO 공식 payload 형태.
// 임의 필드명을 만들지 않고 조사에서 확인된 필드(moCnt, moLst[].moKey/moNumber/moType/moCallback/
// productCode/moTitle/moMsg/telco/moRecvDt/contentCnt/contentInfoLst)만 그대로 사용한다. 이 기능에서 실제로
// 쓰는 것은 moKey/moNumber/moType/moCallback/moMsg/moRecvDt뿐이므로 나머지는 매핑하되 검증하지 않는다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record UplusMoWebhookRequest(
        Integer moCnt,

        @NotEmpty
        @Valid
        List<MoItem> moLst
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoItem(
            @NotBlank String moKey,
            @NotBlank String moNumber,
            @NotBlank String moType,
            @NotBlank String moCallback,
            String productCode,
            String moTitle,
            @NotNull String moMsg,
            String telco,
            @NotBlank String moRecvDt,
            Integer contentCnt,
            List<Object> contentInfoLst
    ) {
    }
}
