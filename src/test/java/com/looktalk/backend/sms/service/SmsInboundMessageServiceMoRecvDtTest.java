package com.looktalk.backend.sms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

// U+ MO moRecvDt 파싱(§3) - 공식 예시("2024-01-29T09:22:55", 출처: doc.msghub.uplus.co.kr LG U+ 메시지허브
// MO API 공식 문서, 이번 세션 직접 fetch로 확인)이 정상 parse되고, Asia/Seoul 기준으로 정확히 변환되며,
// malformed/null/blank만 현재 시각 fallback으로 빠지는지 검증한다. 다른 의존성은 이 메서드에서 쓰이지 않으므로
// 전부 null로 인스턴스를 만든다.
class SmsInboundMessageServiceMoRecvDtTest {

    private final SmsInboundMessageService service =
            new SmsInboundMessageService(null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void parsesOfficialExampleTimestamp() {
        Instant result = service.parseMoRecvDt("2024-01-29T09:22:55");

        Instant expected = ZonedDateTime.of(2024, 1, 29, 9, 22, 55, 0, ZoneId.of("Asia/Seoul")).toInstant();
        assertEquals(expected, result);
    }

    @Test
    void convertsAsiaSeoulLocalTimeToCorrectInstant() {
        // Asia/Seoul(UTC+9) 09:22:55 -> UTC 00:22:55이어야 한다.
        Instant result = service.parseMoRecvDt("2024-01-29T09:22:55");

        assertEquals(Instant.parse("2024-01-29T00:22:55Z"), result);
    }

    @Test
    void malformedTimestampFallsBackToNow() {
        Instant before = Instant.now();
        Instant result = service.parseMoRecvDt("not-a-timestamp");
        Instant after = Instant.now();

        assertTrue(!result.isBefore(before) && !result.isAfter(after),
                "malformed 값은 현재 시각으로 대체되어야 한다.");
    }

    @Test
    void oldNumericFormatIsTreatedAsMalformedAndFallsBack() {
        // 예전에 임시로 가정했던 "yyyyMMddHHmmss" 형식은 공식 포맷이 아니므로 이제는 malformed로 처리되어
        // fallback되어야 한다(공식 ISO_LOCAL_DATE_TIME만 정상 처리 대상이다).
        Instant before = Instant.now();
        Instant result = service.parseMoRecvDt("20240129092255");
        Instant after = Instant.now();

        assertTrue(!result.isBefore(before) && !result.isAfter(after));
    }

    @Test
    void nullFallsBackToNow() {
        Instant result = service.parseMoRecvDt(null);
        assertNotNull(result);
    }

    @Test
    void blankFallsBackToNow() {
        Instant result = service.parseMoRecvDt("   ");
        assertNotNull(result);
    }
}
