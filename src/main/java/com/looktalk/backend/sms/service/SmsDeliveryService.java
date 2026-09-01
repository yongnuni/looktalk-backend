package com.looktalk.backend.sms.service;

import com.looktalk.backend.sms.entity.SmsDeliveryStatus;
import com.looktalk.backend.sms.entity.SmsMessageDelivery;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.repository.SmsMessageDeliveryRepository;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// SMS 친구채팅(outbound) delivery 상태 전이를 관리한다. 각 메서드는 짧은 자기 완결 transaction으로,
// SOLAPI HTTP 호출(ChatMessageService)이나 webhook 처리와 트랜잭션을 공유하지 않는다.
@Service
public class SmsDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryService.class);
    private static final int MAX_STATUS_MESSAGE_LENGTH = 200;

    private final SmsMessageDeliveryRepository deliveryRepository;

    public SmsDeliveryService(SmsMessageDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    // Provider가 발송 요청을 accept한 직후 호출한다. "accept"는 "delivered"가 아니므로 여기서는 SENT까지만
    // 표시하고, 최종 DELIVERED는 §17 SINGLE-REPORT webhook(applyReport)에서만 표시한다.
    @Transactional
    public void markSent(Long smsDeliveryId, String providerMessageId) {
        SmsMessageDelivery delivery = getOrThrow(smsDeliveryId);
        delivery.setProviderMessageId(providerMessageId);
        delivery.setDeliveryStatus(SmsDeliveryStatus.SENT);
        delivery.setUpdatedAt(Instant.now());
    }

    @Transactional
    public void markFailed(Long smsDeliveryId, String statusCode, String statusMessage) {
        SmsMessageDelivery delivery = getOrThrow(smsDeliveryId);
        delivery.setDeliveryStatus(SmsDeliveryStatus.FAILED);
        delivery.setProviderStatusCode(statusCode);
        delivery.setProviderStatusMessage(truncate(statusMessage));
        delivery.setUpdatedAt(Instant.now());
    }

    // SOLAPI SINGLE-REPORT 반영. provider messageId로 찾지 못하면 UNKNOWN_MESSAGE_ID를 반환한다(호출부가
    // 404 등으로 처리). 이미 DELIVERED/FAILED(최종 상태)면 값을 덮어쓰지 않고 ALREADY_FINAL을 반환한다 -
    // 이것이 "동일 SINGLE-REPORT 반복 수신"과 "상태 역행"을 동시에 막는다.
    // dateReceived: 공식 문서(solapi.com/developers/api/webhook 직접 fetch로 확인)상 "단말 수신 시각"이다
    // (dateProcessed="처리 완료 시각", dateReported="리포트 수신 시각"과는 다른 의미). statusCode=4000(최종
    // 성공)일 때만 이 값을 deliveredAt에 사용하고, null/blank/parse 실패면 webhook 처리 시각으로 대체한다.
    // FAILED/SENT에서는 deliveredAt을 설정하지 않는다.
    @Transactional
    public ReportApplyResult applyReport(
            String providerMessageId, String statusCode, String statusMessage, String dateReceived
    ) {
        Optional<SmsMessageDelivery> found =
                deliveryRepository.findByProviderAndProviderMessageId(SmsProvider.SOLAPI, providerMessageId);
        if (found.isEmpty()) {
            return ReportApplyResult.UNKNOWN_MESSAGE_ID;
        }

        SmsMessageDelivery delivery = found.get();
        if (isFinal(delivery.getDeliveryStatus())) {
            return ReportApplyResult.ALREADY_FINAL;
        }

        SmsDeliveryStatus mapped = mapSolapiStatusCode(statusCode);
        delivery.setDeliveryStatus(mapped);
        delivery.setProviderStatusCode(statusCode);
        delivery.setProviderStatusMessage(truncate(statusMessage));
        if (mapped == SmsDeliveryStatus.DELIVERED) {
            delivery.setDeliveredAt(parseDateReceivedOrNow(dateReceived));
        }
        delivery.setUpdatedAt(Instant.now());
        return ReportApplyResult.APPLIED;
    }

    private Instant parseDateReceivedOrNow(String dateReceived) {
        if (dateReceived == null || dateReceived.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(dateReceived);
        } catch (DateTimeException exception) {
            log.warn("SOLAPI dateReceived 형식을 파싱하지 못해 현재 시각으로 대체합니다.");
            return Instant.now();
        }
    }

    private boolean isFinal(SmsDeliveryStatus status) {
        return status == SmsDeliveryStatus.DELIVERED || status == SmsDeliveryStatus.FAILED;
    }

    // SOLAPI SINGLE-REPORT의 statusCode -> Look Talk delivery status 매핑.
    // 출처(이번 세션에 solapi.com/message-status-codes, solapi.com/guides/message-status-codes/ 두 개의
    // 독립된 공식 페이지를 직접 fetch해 교차 확인): SOLAPI 상태 코드는 앞자리로 구분된다.
    //   2000       = "정상 접수(이통사로 접수 예정)" - 접수 단계, 최종 상태 아님
    //   3000       = "이통사로 접수(리포트를 기다리는 중)" - 처리 중, 최종 상태 아님
    //   4000       = "수신자에게 정상 전달 완료" - 유일한 최종 성공 코드
    //   1XXX       = 발송불가(형식/등록 오류) - 최종 실패
    //   2XXX(2000 제외) = 플랫폼 내부 검증 실패 - 최종 실패
    //   3XXX(3000 제외) = 이통사가 수신자에게 발송을 시도했으나 실패 - 최종 실패
    // 2000/3000은 "아직 확정되지 않은 접수/처리 중" 상태이므로 FAILED로 확정하면 안 된다(그러면 나중에 4000이
    // 와도 이미 최종 FAILED로 잠겨 있어 DELIVERED로 갱신되지 못한다 - isFinal() 가드가 최종 상태만 보호하기
    // 때문이다). SmsDeliveryStatus.SENT는 이미 "Provider가 접수했지만 최종 배달 여부는 아직 모름"이라는
    // 의미로 존재하므로(markSent) 2000/3000을 여기서도 SENT로 매핑해 재사용한다 - SENT는 isFinal()에 포함되지
    // 않으므로 이후 DELIVERED/FAILED로 정상 전이할 수 있다.
    // 방어적 처리: SINGLE-REPORT가 공식적으로는 "확정된 결과만" 보낸다고 하더라도, 예상 밖의 2000/3000이 오는
    // 경우까지 대비해 이 매핑을 유지한다.
    private SmsDeliveryStatus mapSolapiStatusCode(String statusCode) {
        if ("4000".equals(statusCode)) {
            return SmsDeliveryStatus.DELIVERED;
        }
        if ("2000".equals(statusCode) || "3000".equals(statusCode)) {
            return SmsDeliveryStatus.SENT;
        }
        return SmsDeliveryStatus.FAILED;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_STATUS_MESSAGE_LENGTH ? value.substring(0, MAX_STATUS_MESSAGE_LENGTH) : value;
    }

    private SmsMessageDelivery getOrThrow(Long smsDeliveryId) {
        return deliveryRepository.findById(smsDeliveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "sms_message_delivery " + smsDeliveryId + "를 찾을 수 없습니다."));
    }

    public enum ReportApplyResult {
        APPLIED,
        ALREADY_FINAL,
        UNKNOWN_MESSAGE_ID
    }
}
