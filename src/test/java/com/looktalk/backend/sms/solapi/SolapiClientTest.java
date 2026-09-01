package com.looktalk.backend.sms.solapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

// SOLAPI HMAC-SHA256 인증(developers.solapi.dev/references/authentication/api-key 기준: Authorization:
// HMAC-SHA256 apiKey=..., date=..., salt=..., signature=hex(HMAC-SHA256(apiSecret, date+salt)))이 정확히
// 구현되어 있는지 실제 네트워크 호출 없이 검증한다.
class SolapiClientTest {

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^HMAC-SHA256 apiKey=([^,]+), date=([^,]+), salt=([^,]+), signature=([0-9a-f]+)$");

    @Test
    void authorizationHeaderMatchesOfficialHmacFormat() throws Exception {
        String apiKey = "test-api-key";
        String apiSecret = "test-api-secret";
        SolapiClient client = new SolapiClient(apiKey, apiSecret);

        String header = client.buildAuthorizationHeader();

        Matcher matcher = HEADER_PATTERN.matcher(header);
        assertTrue(matcher.matches(), "Authorization header 형식이 공식 스펙과 다릅니다: " + header);

        assertEquals(apiKey, matcher.group(1));
        String date = matcher.group(2);
        String salt = matcher.group(3);
        String signature = matcher.group(4);

        // date는 초 단위까지 truncate된 ISO-8601 UTC(예: 2024-01-01T00:00:00Z)여야 한다.
        assertTrue(date.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"), "date 형식이 ISO-8601 UTC가 아닙니다: " + date);
        // salt는 UUID 기반 32자 hex 문자열이다(공식 문서 예시 salt 길이 범위 안에 들어간다).
        assertEquals(32, salt.length());

        // signature = hex(HMAC-SHA256(key=apiSecret, message=date+salt))를 독립적으로 재계산해 비교한다.
        String expectedSignature = hmacSha256Hex(apiSecret, date + salt);
        assertEquals(expectedSignature, signature);
    }

    @Test
    void signIsDeterministicForSameInputAndDiffersForDifferentSecret() {
        SolapiClient clientA = new SolapiClient("key", "secret-a");
        SolapiClient clientB = new SolapiClient("key", "secret-b");

        String data = "2024-01-01T00:00:00Zsomesalt";

        assertEquals(clientA.sign(data), clientA.sign(data));
        assertTrue(!clientA.sign(data).equals(clientB.sign(data)));
    }

    // §5/§32 - "credential 누락" fail-fast. outbound-provider=SOLAPI로 선택되었는데 api-key/api-secret이
    // 비어 있으면 애플리케이션 기동 단계에서 즉시 실패해야 한다(SmtpEmailSender와 동일한 원칙).
    @Test
    void constructorFailsFastWhenApiKeyMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> new SolapiClient("", "secret"));
    }

    @Test
    void constructorFailsFastWhenApiSecretMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> new SolapiClient("key", ""));
    }

    private String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
