# SMS 로컬 양방향 데모 (Cloudflare Tunnel)

AWS/Railway 등에 배포하지 않고, 로컬에서 실행 중인 Backend/Frontend만으로 실제 휴대폰과
양방향 SMS(SOLAPI outbound + LG U+ MessageHub MO inbound)를 주고받기 위한 가이드다.

기존에 이미 구현되어 있는 SOLAPI outbound / U+ MO inbound / SMS room / E2EE / WebSocket
구조를 그대로 사용하며, 이 문서는 그것을 로컬에서 외부와 연결하는 방법만 다룬다. 새로운 SMS
아키텍처나 별도 debug endpoint는 추가하지 않는다.

> **U+ MO 번호 배정 없이 "발송만" 먼저 시연하고 싶다면 0번 섹션만 읽으면 된다.** 1번 이후
> (U+ inbound, Cloudflare Tunnel)는 답장 수신까지 포함한 전체 양방향 데모를 위한 것이며,
> 발송 전용 데모에는 필요하지 않다.

## 0. 발송 전용(단방향) 최소 데모 — Cloudflare/U+ 불필요

친구에게 실제 SMS를 보내기만 하면 되는 가장 빠른 경로다. U+ MO 번호 배정
(`sms_inbound_number_assignment`)이 아직 없는 환자도 시연할 수 있도록, `ChatMessageService`에
**local/demo 전용 발신번호 fallback**을 추가했다(정상 production 정책은 바뀌지 않았다).

발신번호 선택 순서(`ChatMessageService.resolveFromPhone`):

1. 환자에게 active한 `sms_inbound_number_assignment`가 있으면 **항상** 그 `serviceNumber`를 사용한다
   (기존 production 정책, 변경 없음 — 이래야 나중에 U+ 답장이 같은 번호로 돌아온다).
2. assignment가 없고, `looktalk.sms.chat-sender-fallback.enabled`(`SMS_CHAT_SENDER_FALLBACK_ENABLED`)가
   `true`이며, `looktalk.sms.solapi.chat-sender-number`(`SOLAPI_CHAT_SENDER_NUMBER`)가 설정돼 있으면
   그 번호를 발신번호로 사용한다.
3. 둘 다 없으면 기존과 동일하게 `SMS_REPLY_NUMBER_NOT_ASSIGNED`(409)로 실패한다.

필요한 환경변수(값은 이 문서에 적지 않는다):

```text
SPRING_PROFILES_ACTIVE=local

SMS_OUTBOUND_PROVIDER=SOLAPI
SOLAPI_API_KEY=
SOLAPI_API_SECRET=

# 친구채팅(CHAT-006) 전용 발신번호. SOLAPI_OTP_SENDER_NUMBER(인증문자용)와는 별개 값이다.
SOLAPI_CHAT_SENDER_NUMBER=
# U+ MO 번호 배정이 없는 환자도 위 번호로 발송을 허용하려면 true로 설정한다(기본값 false).
SMS_CHAT_SENDER_FALLBACK_ENABLED=true
```

주의:
- `SOLAPI_CHAT_SENDER_NUMBER`는 **SOLAPI에 실제로 발신번호로 등록된 번호**여야 한다(등록되지
  않은 번호로는 SOLAPI가 발송 자체를 거부한다). U+ MO 번호와는 무관하다.
- 이 fallback은 발송(outbound)에만 적용된다. 답장 수신(inbound)은 이번 단계 범위 밖이며,
  U+ MO 번호 배정이 없으므로 친구가 답장해도 Look Talk으로 돌아오지 않는다(의도된 동작).
- U+ MO 번호가 있는 환자는 1번 규칙이 항상 우선하므로 이 fallback은 아무 영향을 주지 않는다.

실행 순서:

```powershell
docker compose up -d
$env:SPRING_PROFILES_ACTIVE = "local"
$env:SMS_OUTBOUND_PROVIDER = "SOLAPI"
$env:SOLAPI_API_KEY = "<실제 값>"
$env:SOLAPI_API_SECRET = "<실제 값>"
$env:SOLAPI_CHAT_SENDER_NUMBER = "<SOLAPI에 등록된 실제 발신번호>"
$env:SMS_CHAT_SENDER_FALLBACK_ENABLED = "true"
.\gradlew bootRun
```

```bash
cd looktalk-frontend
npm run dev
```

그 다음: 테스트 환자로 로그인 → 마이페이지에서 친구를 실제 휴대폰 번호로 등록(SMS 인증 완료
후) → 친구 채팅에서 짧은 문장을 전송 → 친구의 실제 휴대폰 기본 문자 앱에 도착하는지 확인한다.
Cloudflare Tunnel이나 U+ webhook 등록은 필요 없다.

## 1. (양방향 데모) 아키텍처

```text
[환자 -> 지인]

Look Talk Frontend (localhost:5173)
        |
        v
Look Talk Backend (localhost:8080)   <- ChatMessageService.sendSmsMessage()
        |
        v
   SOLAPI (실제 SMS 발송 API)
        |
        v
   지인의 실제 휴대폰 (기본 문자 앱)


[지인 -> 환자]

지인의 실제 휴대폰 (기본 문자 앱)
        |
        v  (같은 U+ MO 서비스 번호로 답장)
   LG U+ 메시지허브 MO webhook
        |
        v
Cloudflare Quick Tunnel (https://xxxxx.trycloudflare.com)
        |
        v
Look Talk Backend (localhost:8080)   <- UplusMoInboundController -> SmsInboundMessageService
        |                                 (room 매칭 -> E2EE 암호화 -> DB 저장)
        v
   WebSocket(STOMP) 실시간 publish
        |
        v
Look Talk Frontend (localhost:5173) 환자 채팅창
```

Cloudflare Tunnel은 `localhost:8080`을 외부 HTTPS로 노출하는 용도로만 쓰는 개발용 도구다.
Backend 코드에는 Cloudflare 전용 코드가 없고, 있을 필요도 없다(순수 reverse tunnel).

## 2. 실제 controller path (source of truth)

| 용도 | Method | Path |
| --- | --- | --- |
| 친구에게 SMS 발송(outbound) | POST | `/api/chat-rooms/{roomId}/messages` (SMS room, `content` 필드) |
| U+ MO inbound webhook | POST | `/api/integrations/sms/inbound/uplus/{token}` |
| SOLAPI SINGLE-REPORT(발송 결과) webhook | POST | `/api/integrations/sms/reports/solapi` |
| 환자에게 SMS 회신 번호 배정(STAFF 전용) | POST | `/api/staff/patients/{patientUserId}/sms-inbound-number` |
| 환자의 SMS 회신 번호 배정 해제(STAFF 전용) | DELETE | `/api/staff/patients/{patientUserId}/sms-inbound-number` |

관련 구현:
- outbound: `ChatMessageService.sendSmsMessage()` (`src/main/java/.../chat/service/ChatMessageService.java`)
- SOLAPI adapter: `sms/solapi/SolapiClient.java`, `SolapiSmsMessageSender.java`
- inbound webhook: `sms/controller/UplusMoInboundController.java` -> `sms/service/SmsInboundMessageService.java`
- delivery report webhook: `sms/controller/SolapiReportWebhookController.java` -> `sms/service/SmsDeliveryService.java`
- 번호 배정: `sms/controller/SmsInboundNumberAssignmentController.java`

`UplusMoInboundController`/`SolapiReportWebhookController`만 `SecurityConfig`에서
`permitAll`이며(§16 표), 각각 URL의 고엔트로피 token(`UPLUS_WEBHOOK_TOKEN`)과
`X-Solapi-Secret` 헤더 검증(`SOLAPI_WEBHOOK_SECRET`을 SHA-1 해시한 값과 비교)으로
실질적인 인증 경계를 둔다.

## 3. 필요한 환경 변수(값은 이 문서에 적지 않는다)

`SPRING_PROFILES_ACTIVE=local`로 기동하면 `application-local.yml`이 대부분 기본값을
채워주지만, 실제 SOLAPI/U+ 연동을 켜려면 아래 변수를 직접 설정해야 한다.

```text
SPRING_PROFILES_ACTIVE=local

# SMS
SMS_OUTBOUND_PROVIDER=SOLAPI
SMS_INBOUND_PROVIDER=UPLUS

SOLAPI_API_KEY=
SOLAPI_API_SECRET=
SOLAPI_OTP_SENDER_NUMBER=
SOLAPI_WEBHOOK_SECRET=

UPLUS_WEBHOOK_TOKEN=

# DB (docker compose 기본값과 다르게 쓸 때만)
# application-local.yml 기본값: jdbc:postgresql://127.0.0.1:5433/looktalk / looktalk / looktalk

# JWT / OTP (application-local.yml에 local 기본값이 있으나, 배포 수준 보안이 필요하면 재정의)
JWT_SECRET=
OTP_HMAC_SECRET=

# CORS (기본값이 이미 http://localhost:5173이므로 로컬 데모에서는 보통 그대로 둔다)
CORS_ALLOWED_ORIGINS=http://localhost:5173

# Mail(SMS 데모와 무관하지만 기동에 필요)
MAIL_PROVIDER=logging
```

주의:
- `SMS_OUTBOUND_PROVIDER=SOLAPI`로 설정하면 `SolapiClient`가 기동 시점에
  `SOLAPI_API_KEY`/`SOLAPI_API_SECRET`이 비어 있으면 즉시 fail-fast로 기동을 막는다
  (`Assert.hasText`). 두 값을 반드시 함께 설정한다.
- `SOLAPI_WEBHOOK_SECRET`이 비어 있으면 `SolapiReportWebhookController`가 모든 요청을
  거부한다(`requireValidSecret`). SOLAPI 콘솔에 등록한 webhook secret과 반드시 같은 값으로 맞춘다.
- `UPLUS_WEBHOOK_TOKEN`이 비어 있으면 `UplusMoInboundController`가 모든 요청을 거부한다.
- 위 값들은 절대 코드/설정 파일에 하드코딩하지 않는다. OS 환경 변수 또는
  `.gitignore` 대상 로컬 파일(예: 실행 시 셸에서 `set`/`$env:`로 주입)로만 관리한다.

## 4. 실행 순서

### 4.1 Backend

```powershell
docker compose up -d
$env:SPRING_PROFILES_ACTIVE = "local"
$env:SMS_OUTBOUND_PROVIDER = "SOLAPI"
$env:SMS_INBOUND_PROVIDER = "UPLUS"
$env:SOLAPI_API_KEY = "<실제 값>"
$env:SOLAPI_API_SECRET = "<실제 값>"
$env:SOLAPI_WEBHOOK_SECRET = "<실제 값>"
$env:UPLUS_WEBHOOK_TOKEN = "<실제 값>"
.\gradlew bootRun
```

Backend가 `http://localhost:8080`에서 실행된다.

### 4.2 Frontend

```bash
cd looktalk-frontend
npm run dev
```

Frontend가 `http://localhost:5173`에서 실행된다(Backend `application.yml`의
`CORS_ALLOWED_ORIGINS` 기본값이 이미 이 origin을 허용한다).

### 4.3 Cloudflare Tunnel

```powershell
cloudflared tunnel --url http://localhost:8080
```

출력 로그에서 아래 형태의 URL을 복사한다.

```text
https://xxxxx.trycloudflare.com
```

**Quick Tunnel URL은 `cloudflared`를 재실행할 때마다 바뀐다.** URL이 바뀌면 아래 5번의
webhook URL도 U+/SOLAPI 콘솔에 다시 등록해야 한다.

## 5. 최종 webhook URL 등록

Tunnel URL을 `{TUNNEL_URL}`이라 하면:

- U+ MO inbound webhook (U+ 메시지허브 콘솔에 등록):
  `{TUNNEL_URL}/api/integrations/sms/inbound/uplus/{UPLUS_WEBHOOK_TOKEN}`
- SOLAPI 발송 결과(SINGLE-REPORT) webhook (SOLAPI 콘솔에 등록):
  `{TUNNEL_URL}/api/integrations/sms/reports/solapi`

## 6. 환자에게 U+ MO 서비스 번호 배정

친구에게 보내는 SMS의 발신번호이자, 친구가 답장했을 때 되돌아오는 수신번호다
(`sms_inbound_number_assignment`). 이미 확보한 번호를 STAFF 계정으로 배정한다.

```bash
curl -X POST \
  http://localhost:8080/api/staff/patients/{patientUserId}/sms-inbound-number \
  -H "Authorization: Bearer {STAFF_ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"serviceNumber": "0212345678"}'
```

배정 없이 친구에게 SMS를 보내려 하면 `SMS_REPLY_NUMBER_NOT_ASSIGNED`(409)로 실패한다
(`ChatMessageService.sendSmsMessage`).

또한 발송/수신 전에 확인해야 하는 것:
- 친구(외부 참가자)의 실제 전화번호가 `friendship.friend_phone`에 저장되어 있어야 한다
  (친구 등록 API, `PhoneNormalizer.normalize`로 숫자만 남긴 형태).
- 환자 본인의 E2EE public key가 등록되어 있어야 한다(`UserE2eeKey`) — 없으면 발송/수신 모두
  각각 `E2EE_KEY_REQUIRED` 또는 `REJECTED_NO_E2EE_KEY`로 막힌다.
- 환자 본인의 휴대폰 SMS 인증(`isSmsVerified`)이 완료돼 있어야 한다.

## 7. U+ inbound를 실제 문자 없이 로컬에서 먼저 검증하기

실제 U+ webhook을 연결하기 전에, 이미 구현된 `UplusMoInboundController`를 curl로 직접
호출해 inbound 처리(room 매칭, E2EE 저장, WebSocket publish)를 검증할 수 있다. 별도의
debug bypass endpoint는 없다 — 실제 webhook endpoint를 그대로 호출한다.

```bash
curl -X POST \
  http://localhost:8080/api/integrations/sms/inbound/uplus/{UPLUS_WEBHOOK_TOKEN} \
  -H "Content-Type: application/json" \
  -d '{
    "moCnt": 1,
    "moLst": [
      {
        "moKey": "test-mo-key-0001",
        "moNumber": "0212345678",
        "moType": "SMSMO",
        "moCallback": "01099990000",
        "moMsg": "4시에 갈게",
        "moRecvDt": "2026-09-01T10:00:00"
      }
    ]
  }'
```

값 설명(전부 fake 값으로 채워야 한다):
- `moNumber`: 6번에서 환자에게 배정한 `serviceNumber`와 동일해야 한다.
- `moCallback`: 그 환자의 친구로 등록된 `friend_phone`(정규화된 숫자만)과 동일해야 한다.
- `moKey`: provider+moKey idempotency 키다. 같은 값으로 다시 보내면 두 번째 요청은
  무시된다(`sms_inbound_receipt` 유니크 제약).
- `moRecvDt`: U+ 공식 포맷(`ISO_LOCAL_DATE_TIME`, timezone 없음, Asia/Seoul 현지 시각으로
  해석)을 그대로 따른다.

성공 시 응답은 `{"code":"10000","message":"success"}`다. `moNumber`/`moCallback`이
매칭되지 않거나 E2EE key가 없어도 U+ 재시도를 유발하지 않기 위해 동일하게 `10000`으로
응답하며(내부적으로는 `sms_inbound_receipt.processing_status`에 실패 사유가 남는다), 이는
버그가 아니라 의도된 동작이다(`UplusMoInboundController`, `SmsInboundMessageService` 참고).

## 8. 실제 휴대폰 E2E 절차

1. Backend/Frontend/Cloudflare Tunnel을 4번대로 모두 실행한다.
2. U+ 콘솔에 5번의 inbound webhook URL을, SOLAPI 콘솔에 5번의 delivery webhook URL을 등록한다.
3. 6번대로 환자에게 U+ MO 서비스 번호를 배정한다.
4. 친구를 실제 휴대폰 번호로 등록한다(마이페이지 친구 관리, SMS 인증 완료 후).
5. Look Talk 친구 채팅에서 그 친구에게 메시지를 보낸다 → SOLAPI 응답 200 확인 →
   친구의 실제 휴대폰에 문자가 도착하는지 확인한다.
6. 친구가 받은 문자에 그대로 답장한다(같은 U+ MO 번호로) → Cloudflare Tunnel 로그에
   POST 요청이 찍히는지 확인 → Look Talk 환자 화면에 실시간으로 답장이 나타나는지 확인한다.

## 9. 알려진 외부 제약(코드로 해결할 수 없는 부분)

- **U+ MO 서비스 번호를 SOLAPI 발신번호로 실제 등록/사용할 수 있는지는 코드 문제가 아니라
  SOLAPI 발신번호 사전등록 제도 및 U+ 메시지허브 계약 조건에 달려 있다.** 이 프로젝트의
  코드 구조(`SmsInboundNumberAssignment.serviceNumber`를 SOLAPI 발신번호로 그대로
  사용, `ChatMessageService.sendSmsMessage`)는 "U+ MO 번호 = SOLAPI 발신번호"라는 전제가
  성립한다면 정상 동작하도록 이미 맞춰져 있지만, 실제로 그 번호를 SOLAPI에서 발신번호로
  승인받을 수 있는지는 각 provider 콘솔/영업 담당자를 통해 별도로 확인해야 한다.
- U+ 메시지허브 MO webhook에 대해 공식 서명/인증 헤더가 있는지 이번 조사에서
  최종 확인하지 못했다(`UplusMoInboundController` 주석 참고) — 그래서 URL 경로 token 방식을
  쓰고 있다. rate limiting도 이번 범위에는 없다.
- Quick Tunnel URL은 세션마다 바뀌므로, 데모를 새로 시작할 때마다 4.3 이후 단계를 다시
  수행해야 한다(고정 도메인이 필요하면 Cloudflare Named Tunnel을 별도로 구성해야 하며,
  이는 이 문서의 범위 밖이다).
