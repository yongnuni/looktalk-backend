# Look Talk Backend API 명세서 v2.1

## 1. 구현 범위

이번 백엔드 구현에서는 다음 기능을 구현한다.

| Domain | 구현 기능 |
|---|---|
| Auth | 이메일 인증, 아이디 중복확인, 환자 가입, 의료진 가입, 로그인, Access Token 재발급, 로그아웃, 비밀번호 재설정 |
| User | 내 정보 조회, 이름 설정·수정·삭제, 전화번호 인증, 회원탈퇴 |
| UserSetting | 키보드 레이아웃, 키 확대 여부, 현재 입력 방식 조회·수정 |
| Staff | 의료진 정보 조회, 담당 환자 조회·등록·해제 |
| E2EE | 사용자 공개키 등록·조회, 개인 콘텐츠 및 내부 채팅용 종단간 암호화 지원 |
| Memo | E2EE 기반 개인 메모 CRUD |
| Phrase | E2EE 기반 자주 쓰는 문장 CRUD, 최대 3개 FIFO 유지 |
| Friend | 전화번호 인증 기반 외부 SMS 지인 CRUD |
| Chat | 내부 회원 연락처 조회, 병원 채팅방, SMS 채팅방, 메시지 조회·전송 |
| WebSocket | 암호화된 실시간 메시지 전달 |

다음 기능은 현재 구현하지 않는다.

```text
Calibration
Recommendation
Emergency
Device
Jetson Nano
책임의료진
관리자
E2EE 다중 디바이스 키 동기화
E2EE 키 복구
SMS delivery status 추적
InputSession
```

`InputSession`은 입력 지표, 단위, 저장 시점과 API 계약이 확정되지 않았으므로 현재 구현 범위에서 제외한다. 기존 `input_session_log` 테이블은 유지하되 현재 백엔드 코드에서는 사용하지 않는다.

---

# 2. DB 기준 사용자 구조

회원은 모두 `users`에 저장하고 `role`로 사용자 유형을 구분한다.

```text
users
 ├─ role = PATIENT
 │
 └─ role = STAFF
          │
          └─ staff
               │
               └─ hospital
```

환자 전용 `patient` 테이블은 사용하지 않는다.

PATIENT는 특정 병원에 소속된 환자만을 의미하지 않는다. 병원이나 의료진과 연결되지 않은 개인 사용자도 `role=PATIENT`로 가입하고 Look Talk의 환자 기능을 사용한다.

사용자 주요 컬럼은 다음 기준으로 사용한다.

| DB 컬럼 | 필수 | 의미 |
|---|---:|---|
| `user_id` | Y | 서버에서 생성하는 내부 사용자 PK |
| `login_id` | Y | 사용자가 회원가입 시 입력하는 로그인 아이디 |
| `user_email` | Y | 이메일 인증 및 비밀번호 재설정에 사용하는 이메일 |
| `user_pwd` | Y | BCrypt로 해시한 비밀번호 |
| `user_name` | N | 마이페이지에서 선택적으로 설정하는 이름 |
| `user_phone` | N | 전화번호 인증 성공 후 저장하는 전화번호 |
| `role` | Y | `PATIENT`, `STAFF` |
| `is_email_verified` | Y | 이메일 인증 완료 여부 |
| `is_sms_verified` | Y | 전화번호 인증 완료 여부 |
| `reset_token` | N | 비밀번호 재설정 토큰의 해시값 |
| `reset_token_expiry` | N | 비밀번호 재설정 토큰 만료 시각 |

`user_name`은 NULL을 허용한다.

로그인 아이디와 이메일은 서로 다른 용도로 사용한다.

```text
login_id
→ 로그인에 사용한다.

user_email
→ 회원가입 이메일 인증에 사용한다.
→ 비밀번호 재설정에 사용한다.
```

---

# 3. API 구현을 위한 추가 DB 구조

Refresh Token과 E2EE를 구현하기 위해 다음 구조를 추가한다.

## 3.1 refresh_token

```text
refresh_token
- refresh_token_id BIGSERIAL PK
- user_id VARCHAR(50) FK users ON DELETE CASCADE
- token_hash VARCHAR(64) NOT NULL UNIQUE
- expires_at TIMESTAMPTZ NOT NULL
- revoked_at TIMESTAMPTZ NULL
- created_at TIMESTAMPTZ NOT NULL
```

Refresh Token 원문은 DB에 저장하지 않는다. SHA-256 등의 방식으로 해시한 값을 저장한다.

## 3.2 user_e2ee_key

```text
user_e2ee_key
- key_id BIGSERIAL PK
- user_id VARCHAR(50) FK users ON DELETE CASCADE
- key_version INTEGER NOT NULL
- public_key TEXT NOT NULL
- created_at TIMESTAMPTZ NOT NULL
- revoked_at TIMESTAMPTZ NULL

UNIQUE(user_id, key_version)
```

서버에는 공개키만 저장한다. 개인키는 브라우저에서 생성하고 브라우저 저장소에 보관하며 Backend로 전송하지 않는다.

## 3.3 message_ciphertext

병원 채팅 메시지는 사용자별 ciphertext를 따로 저장한다.

```text
message_ciphertext
- message_ciphertext_id BIGSERIAL PK
- message_id BIGINT FK message ON DELETE CASCADE
- recipient_user_id VARCHAR(50) FK users ON DELETE CASCADE
- key_version INTEGER NOT NULL
- ciphertext TEXT NOT NULL

UNIQUE(message_id, recipient_user_id)
```

내부 1:1 채팅에서는 같은 평문 메시지를 송신자 공개키와 수신자 공개키 각각으로 암호화한 두 개의 ciphertext를 저장한다.

SMS 채팅에서는 환자 본인의 공개키로 암호화한 ciphertext만 저장한다.

## 3.4 memo / phrase 암호화 저장

`memo.content`, `phrase.phrase_text`에는 평문을 저장하지 않고 Base64 ciphertext를 저장한다.

암호화에 사용한 공개키 버전을 식별하기 위해 다음 컬럼을 추가한다.

```text
memo.key_version
phrase.key_version
```

## 3.5 message 본문 저장

`message` 테이블에는 평문 메시지 본문을 저장하지 않는다.

E2EE 적용 이후 `message.content` 컬럼은 제거하고 `message`에는 다음 메타데이터만 저장한다.

```text
message_id
room_id
sender_participant_id
message_type
created_at
```

실제 메시지 본문은 `message_ciphertext`에 사용자별 ciphertext로 저장한다.

## 3.6 chat_room.conversation_key

동일 상대와의 1:1 채팅방이 중복 생성되지 않도록 `chat_room`에 `conversation_key VARCHAR(200)`을 사용한다.

병원 채팅은 두 userId를 정렬한 뒤 다음 형식으로 생성한다.

```text
HOSPITAL:{smallUserId}:{largeUserId}
```

SMS 채팅은 다음 형식으로 생성한다.

```text
SMS:{currentUserId}:{normalizedFriendPhone}
```

`conversation_key`에는 NULL이 아닌 값에 대해 UNIQUE 인덱스를 적용한다.

## 3.7 verification_code.code_hash

인증번호 원문은 DB에 저장하지 않는다.

기존 `verification_code.code`는 `code_hash VARCHAR(64)`로 변경하고 이메일/SMS 인증번호의 HMAC-SHA256 결과를 저장한다.

```text
사용자에게 발송할 6자리 인증번호
→ 서버 비밀키를 이용해 HMAC-SHA256 계산
→ verification_code.code_hash 저장
```

인증 확인 시 사용자가 입력한 코드에 동일한 HMAC 연산을 적용한 뒤 DB의 `code_hash`와 비교한다.

## 3.8 chat_participant.is_withdrawn

USER-005(회원탈퇴) 이후에도 `chat_room`/`message` 이력을 보존해야 하는데, 내부 participant는 `external_phone`이
항상 NULL이라서 탈퇴로 `user_id`까지 NULL이 되면 어떤 회원인지도, 외부 지인인지도 구분할 수 없는 모호한 행이
된다. 이를 명시적으로 표현하기 위해 `chat_participant`에 다음 컬럼을 추가한다.

```text
chat_participant.is_withdrawn BOOLEAN NOT NULL DEFAULT FALSE
```

`chat_participant`의 identity는 다음 세 가지 상태만 허용하며, 그 외 조합(예: 의미 없이 `user_id`/
`external_phone`이 모두 NULL이면서 `is_withdrawn=FALSE`인 행)은 DB CHECK 제약으로 거부한다.

```text
정상 내부 participant:
  user_id IS NOT NULL AND external_phone IS NULL AND is_withdrawn = FALSE

외부 SMS participant:
  user_id IS NULL AND external_phone IS NOT NULL AND is_withdrawn = FALSE

탈퇴한 내부 participant:
  user_id IS NULL AND external_phone IS NULL AND is_withdrawn = TRUE
```

이 세 상태만 허용하도록 `chk_chat_participant_identity` CHECK 제약을 이 컬럼과 함께 재정의한다.

`is_withdrawn`은 DB에서 탈퇴한 내부 participant 상태를 명시적으로 보존하고 CHECK 제약으로 무결성을 보장하기
위한 상태값이다. CHAT-002에서는 HOSPITAL participant의 연결된 user가 NULL인 탈퇴 상태를
`displayName = "탈퇴한 사용자"`로 표시한다(§53 "target (탈퇴한 HOSPITAL 상대)" 참고).

---

# 4. 사용자 표시 이름 정책

Look Talk 내부 사용자는 다음 규칙으로 표시한다.

```text
users.user_name이 존재하고 blank가 아님
→ user_name을 표시한다.

users.user_name이 NULL 또는 blank
→ login_id를 표시한다.
```

API에서는 화면 표시용 필드를 `displayName`으로 반환한다.

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "loginId": "patient01",
  "name": null,
  "displayName": "patient01"
}
```

이름을 설정한 경우 다음과 같이 반환한다.

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "loginId": "patient01",
  "name": "김민준",
  "displayName": "김민준"
}
```

`displayName`은 DB 컬럼으로 저장하지 않는다. Response 생성 시 `user_name → login_id` 순서로 계산한다.

외부 SMS 지인은 Look Talk 회원이 아니므로 `friendship.friend_name`과 `chat_participant.external_name`을 표시한다.

---

# 5. 보안 및 암호화 정책

## 5.1 비밀번호

비밀번호는 평문으로 저장하지 않는다.

```text
사용자 비밀번호
→ BCrypt
→ users.user_pwd
```

로그인 시 `PasswordEncoder.matches()`로 검증한다.

Application Log, 예외 로그, Request dump에 비밀번호를 기록하지 않는다.

## 5.2 Refresh Token

Refresh Token 원문은 DB에 저장하지 않는다.

```text
Refresh Token 원문
→ SHA-256
→ token_hash 저장
```

브라우저에는 `HttpOnly`, `Secure` Cookie로 전달한다.

## 5.3 Reset Token

비밀번호 재설정 토큰은 원문을 사용자에게 전달하고 DB에는 해시값만 저장한다.

## 5.4 이메일과 전화번호

이메일과 전화번호는 서비스 기능에서 실제 값이 필요하므로 현재 컬럼에 원문을 저장한다.

운영 환경에서는 TLS를 적용하고 DB 접근 권한을 최소화한다. 이메일, 전화번호를 Application Log에 기록하지 않는다.

## 5.5 인증번호

이메일 및 SMS 인증번호는 발급 후 만료시간을 설정한다. 인증번호 원문은 DB에 저장하지 않고 서버 비밀키를 이용한 HMAC-SHA256 결과를 `verification_code.code_hash`에 저장한다.

## 5.6 E2EE 적용 범위

다음 사용자 콘텐츠는 E2EE 방식으로 처리한다.

```text
개인 메모
자주 쓰는 문장
Look Talk 내부 회원 간 병원 채팅
```

평문은 Frontend에서 암호화하고 Backend에는 ciphertext만 전달한다.

Backend는 해당 ciphertext를 복호화할 수 있는 개인키를 보유하지 않는다.

## 5.7 SMS 채팅

일반 SMS는 SMS Provider가 평문을 전달받아야 하므로 완전한 E2EE를 적용할 수 없다.

SMS 발신 시 Backend는 평문을 Provider에 전달한 후 사용자의 공개키로 암호화한 ciphertext만 DB에 저장한다.

SMS 수신 시 Provider Webhook으로 받은 평문을 사용자의 공개키로 즉시 암호화한 후 ciphertext만 DB에 저장한다.

SMS 평문은 Application Log와 DB에 저장하지 않는다.

---

# 6. E2EE 처리 방식

E2EE는 `libsodium sealed box` 호환 방식을 사용한다.

API의 암호화 Payload는 다음 형식을 사용한다.

```json
{
  "algorithm": "LIBSODIUM_SEALED_BOX",
  "keyVersion": 1,
  "ciphertext": "BASE64_CIPHERTEXT"
}
```

## 6.1 Base64 인코딩 규칙

공개키(`publicKey`)와 ciphertext는 모두 **RFC 4648 Standard Base64**(`+`, `/`, `=` 패딩 포함)로 인코딩한다.

URL-safe Base64(RFC 4648 Section 5, `-`, `_` 사용)는 사용하지 않는다.

Frontend와 Backend는 인코딩·디코딩 시 항상 Standard Base64 구현을 사용해 동일한 규칙으로 맞춘다.

Frontend는 사용자 공개키와 개인키를 생성한다.

```text
public key
→ Backend에 등록한다.

private key
→ Backend에 전송하지 않는다.
→ 브라우저의 IndexedDB 등에 저장한다.
```

병원 채팅에서 메시지를 전송할 때 동일 평문을 각 participant의 공개키로 각각 암호화한다.

```text
평문
├─ sender public key로 암호화 → senderCiphertext
└─ receiver public key로 암호화 → receiverCiphertext
```

Backend는 두 ciphertext를 `message_ciphertext`에 저장한다.

메시지 조회 시 현재 로그인 사용자의 `recipient_user_id`에 해당하는 ciphertext만 반환한다.

E2EE는 메시지 내용의 기밀성을 보호한다. 다음 메타데이터는 서버에서 확인할 수 있다.

```text
room_id
message_id
sender
recipient
created_at
사용자 role
displayName
```

---

# 7. Base URL

모든 REST API는 다음 Prefix를 사용한다.

```http
/api
```

개발 환경에서는 다음과 같이 호출한다.

```text
http://localhost:8080/api/auth/login
```

---

# 8. Request Header 및 Cookie

JSON 요청은 다음 Header를 사용한다.

```http
Content-Type: application/json
```

Access Token 인증이 필요한 요청은 다음 Header를 사용한다.

```http
Authorization: Bearer {accessToken}
```

Refresh Token은 Frontend JavaScript에서 직접 읽지 않고 HttpOnly Cookie로 관리한다.

```http
Set-Cookie: LOOKTALK_REFRESH={refreshToken}; HttpOnly; Secure; SameSite=Lax; Path=/api/auth
```

배포 구조상 Frontend와 Backend가 cross-site인 경우 `SameSite=None; Secure`와 CORS credentials 설정을 함께 적용한다.

---

# 9. 인증 구조

Spring Security와 JWT를 사용한다.

Access Token에는 최소 다음 값을 저장한다.

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "role": "PATIENT"
}
```

`sub`에는 `users.user_id`를 저장한다.

현재 사용자 식별값은 Request Body에서 받지 않는다.

```text
HTTP Request
→ JwtAuthenticationFilter
→ Access Token 검증
→ userId / role 추출
→ SecurityContext 등록
→ Controller
→ Service
```

Access Token은 짧은 만료시간을 사용하고 Refresh Token은 더 긴 만료시간을 사용한다. 실제 만료시간은 application 설정으로 관리한다.

---

# 10. 권한 구분

| 표기 | 의미 |
|---|---|
| `PUBLIC` | 로그인 없이 호출한다 |
| `AUTH` | PATIENT 또는 STAFF가 호출한다 |
| `PATIENT` | PATIENT만 호출한다 |
| `STAFF` | STAFF만 호출한다 |

Controller 또는 Method Security에서 Role 접근 권한을 적용한다.

소유권, 채팅방 참여 여부, 담당 환자 관계 등의 비즈니스 권한은 Service에서 추가 검증한다.

---

# 11. 공통 성공 Response

모든 REST API는 다음 구조를 사용한다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

공통 DTO는 다음과 같이 구현한다.

```java
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {
}
```

---

# 12. 공통 실패 Response

```json
{
  "success": false,
  "code": "MEMO_NOT_FOUND",
  "message": "메모를 찾을 수 없습니다.",
  "data": null
}
```

Frontend는 `message` 문자열이 아니라 `code`를 기준으로 오류 유형을 처리한다.

---

# 13. HTTP Status 정책

| HTTP | 사용하는 경우 |
|---:|---|
| `200 OK` | 조회, 수정, 삭제, 로그인, 인증, 토큰 재발급 성공 |
| `201 Created` | 회원가입, 메모·문장·친구·채팅방 생성 |
| `400 Bad Request` | Request 형식 오류, 인증번호 오류, 자기 자신 채팅 요청 |
| `401 Unauthorized` | 로그인 실패, Access/Refresh Token 없음·만료·위조 |
| `403 Forbidden` | 인증은 되었으나 역할·소유권·참여 권한이 없음 |
| `404 Not Found` | 대상 리소스가 존재하지 않음 |
| `409 Conflict` | 아이디·이메일·친구·담당 관계 중복, E2EE 키 미등록 상태 충돌 |
| `500 Internal Server Error` | 예상하지 못한 서버 오류 |
| `502 Bad Gateway` | 이메일 또는 SMS Provider 호출 실패 |
| `503 Service Unavailable` | 외부 연동 기능이 설정되지 않음 |

삭제 API는 공통 Response Body를 반환하므로 `204 No Content`를 사용하지 않고 `200 OK`를 사용한다.

---

# 14. Validation 규칙

| 필드 | Validation |
|---|---|
| `loginId` | 필수, 최대 50자 |
| `email` | 필수, 이메일 형식, 최대 100자 |
| `password` | 필수, 8~64자 |
| `name` | 선택, 최대 50자 |
| `phone` | 필수인 API에서 NotBlank, 최대 20자, 서버에서 정규화 |
| `code` | 인증번호 형식 검증 |
| `ciphertext` | 필수, Base64 형식, 최대 payload 크기 제한 |
| `keyVersion` | 1 이상의 정수 |
| `content` | SMS 평문 요청에서 blank 금지 |

암호화된 Memo, Phrase, 병원 메시지의 실제 평문 길이는 Backend가 확인할 수 없으므로 Frontend에서 평문 Validation을 수행하고 Backend에서는 ciphertext 최대 바이트 크기를 제한한다.

Validation 오류는 `GlobalExceptionHandler`에서 처리한다.

```json
{
  "success": false,
  "code": "COMMON_VALIDATION_ERROR",
  "message": "입력값을 확인해주세요.",
  "data": {
    "fieldErrors": [
      {
        "field": "email",
        "reason": "올바른 이메일 형식이 아닙니다."
      }
    ]
  }
}
```

---

# 15. 공통 Error Code

| Error Code | HTTP | 설명 |
|---|---:|---|
| `COMMON_VALIDATION_ERROR` | 400 | Request Validation에 실패한다 |
| `COMMON_UNAUTHORIZED` | 401 | 인증 정보가 존재하지 않는다 |
| `COMMON_TOKEN_INVALID` | 401 | Access Token이 유효하지 않는다 |
| `COMMON_TOKEN_EXPIRED` | 401 | Access Token이 만료된다 |
| `COMMON_FORBIDDEN` | 403 | API 접근 권한이 없다 |
| `COMMON_NOT_FOUND` | 404 | 요청한 API 또는 리소스를 찾을 수 없다 |
| `COMMON_INTERNAL_ERROR` | 500 | 처리하지 못한 서버 오류가 발생한다 |
| `AUTH_DUPLICATE_LOGIN_ID` | 409 | loginId가 이미 존재한다 |
| `AUTH_DUPLICATE_EMAIL` | 409 | 이메일이 이미 가입에 사용된다 |
| `AUTH_INVALID_CREDENTIALS` | 401 | 아이디 또는 비밀번호가 일치하지 않는다 |
| `AUTH_INVALID_VERIFICATION_CODE` | 400 | 인증번호가 일치하지 않는다 |
| `AUTH_VERIFICATION_EXPIRED` | 400 | 인증번호가 만료된다 |
| `AUTH_EMAIL_NOT_VERIFIED` | 400 | 이메일 인증이 완료되지 않는다 |
| `AUTH_INVALID_INVITE_CODE` | 400 | 병원 초대코드가 유효하지 않는다 |
| `AUTH_RESET_TOKEN_INVALID` | 400 | 비밀번호 재설정 토큰이 유효하지 않는다 |
| `AUTH_RESET_TOKEN_EXPIRED` | 400 | 비밀번호 재설정 토큰이 만료된다 |
| `AUTH_REFRESH_TOKEN_MISSING` | 401 | Refresh Token Cookie가 존재하지 않는다 |
| `AUTH_REFRESH_TOKEN_INVALID` | 401 | Refresh Token이 유효하지 않는다 |
| `AUTH_REFRESH_TOKEN_EXPIRED` | 401 | Refresh Token이 만료된다 |
| `USER_NOT_FOUND` | 404 | 사용자 데이터가 존재하지 않는다 |
| `SMS_VERIFICATION_REQUIRED` | 403 | 전화번호 인증이 필요한 기능을 인증 전에 호출한다 |
| `STAFF_NOT_FOUND` | 404 | 의료진 데이터가 존재하지 않는다 |
| `STAFF_PATIENT_NOT_FOUND` | 404 | 대상 환자가 존재하지 않는다 |
| `STAFF_PATIENT_ALREADY_ASSIGNED` | 409 | 동일 담당 관계가 이미 존재한다 |
| `STAFF_PATIENT_NOT_ASSIGNED` | 404 | 담당 관계가 존재하지 않는다 |
| `E2EE_KEY_REQUIRED` | 409 | 현재 사용자 E2EE 공개키가 등록되지 않는다 |
| `E2EE_TARGET_KEY_REQUIRED` | 409 | 상대 사용자 E2EE 공개키가 등록되지 않는다 |
| `E2EE_KEY_NOT_FOUND` | 404 | 지정한 E2EE 공개키가 존재하지 않는다 |
| `E2EE_INVALID_PAYLOAD` | 400 | 암호화 Payload 형식이 올바르지 않는다 |
| `MEMO_NOT_FOUND` | 404 | 메모가 존재하지 않는다 |
| `PHRASE_NOT_FOUND` | 404 | 자주 쓰는 문장이 존재하지 않는다 |
| `FRIEND_NOT_FOUND` | 404 | 지인 데이터가 존재하지 않는다 |
| `FRIEND_ALREADY_EXISTS` | 409 | 같은 전화번호가 이미 등록된다 |
| `CHAT_TARGET_NOT_FOUND` | 404 | 선택한 내부 채팅 상대가 존재하지 않는다 |
| `CHAT_SELF_TARGET` | 400 | 자기 자신을 채팅 상대방으로 지정한다 |
| `CHAT_ROOM_NOT_FOUND` | 404 | 채팅방이 존재하지 않는다 |
| `CHAT_ACCESS_DENIED` | 403 | 해당 채팅방의 participant가 아니다 |
| `SMS_PROVIDER_NOT_CONFIGURED` | 503 | SMS Provider가 설정되지 않는다 |
| `SMS_SEND_FAILED` | 502 | SMS Provider 전송에 실패한다 |

---

# 16. 전체 Endpoint

| ID | Method | Endpoint | 설명 | 권한 |
|---|---|---|---|---|
| AUTH-001 | POST | `/api/auth/email-verifications` | 이메일 인증번호를 발송한다 | PUBLIC |
| AUTH-002 | POST | `/api/auth/email-verifications/confirm` | 이메일 인증번호를 확인한다 | PUBLIC |
| AUTH-003 | GET | `/api/auth/login-id/availability` | 로그인 아이디 중복 여부를 확인한다 | PUBLIC |
| AUTH-004 | POST | `/api/auth/patients/signup` | 환자 계정을 생성한다 | PUBLIC |
| AUTH-005 | POST | `/api/auth/staff/signup` | 의료진 계정을 생성한다 | PUBLIC |
| AUTH-006 | POST | `/api/auth/login` | 아이디·비밀번호를 검증하고 Access/Refresh Token을 발급한다 | PUBLIC |
| AUTH-007 | POST | `/api/auth/token/refresh` | Refresh Token으로 Access Token을 재발급한다 | PUBLIC |
| AUTH-008 | POST | `/api/auth/logout` | 현재 Refresh Token을 폐기하고 로그아웃한다 | PUBLIC |
| AUTH-009 | POST | `/api/auth/password/reset` | 비밀번호를 재설정한다 | PUBLIC |
| USER-001 | GET | `/api/users/me` | 현재 사용자 정보를 조회한다 | AUTH |
| USER-002 | PATCH | `/api/users/me` | 사용자 이름을 설정·수정·삭제한다 | AUTH |
| USER-003 | POST | `/api/users/me/phone-verifications` | 전화번호 인증번호를 발송한다 | PATIENT |
| USER-004 | POST | `/api/users/me/phone-verifications/confirm` | 전화번호 인증을 완료한다 | PATIENT |
| USER-005 | DELETE | `/api/users/me` | 회원탈퇴를 처리한다 | AUTH |
| SET-001 | GET | `/api/users/me/settings` | 사용자 입력 설정을 조회한다 | PATIENT |
| SET-002 | PATCH | `/api/users/me/settings` | 사용자 입력 설정을 수정한다 | PATIENT |
| STAFF-001 | GET | `/api/staff/me` | 의료진·병원 정보를 조회한다 | STAFF |
| STAFF-002 | GET | `/api/staff/me/patients` | 담당 환자를 조회한다 | STAFF |
| STAFF-003 | POST | `/api/staff/me/patients` | 담당 환자를 추가한다 | STAFF |
| STAFF-004 | DELETE | `/api/staff/me/patients/{patientUserId}` | 담당 관계를 해제한다 | STAFF |
| E2EE-001 | POST | `/api/e2ee/keys` | 현재 사용자의 공개키를 등록한다 | AUTH |
| E2EE-002 | GET | `/api/e2ee/keys/me` | 현재 사용자의 공개키 등록 상태를 조회한다 | AUTH |
| E2EE-003 | GET | `/api/e2ee/users/{userId}/public-key` | 내부 회원의 현재 공개키를 조회한다 | AUTH |
| MEMO-001 | GET | `/api/memos` | 암호화된 개인 메모를 조회한다 | PATIENT |
| MEMO-002 | POST | `/api/memos` | 암호화된 개인 메모를 등록한다 | PATIENT |
| MEMO-003 | PATCH | `/api/memos/{memoId}` | 암호화된 개인 메모를 수정한다 | PATIENT |
| MEMO-004 | DELETE | `/api/memos/{memoId}` | 개인 메모를 삭제한다 | PATIENT |
| PHRASE-001 | GET | `/api/phrases` | 자주 쓰는 문장을 조회한다 | PATIENT |
| PHRASE-002 | POST | `/api/phrases` | 자주 쓰는 문장을 등록하고 최대 3개를 유지한다 | PATIENT |
| PHRASE-003 | PATCH | `/api/phrases/{phraseId}` | 자주 쓰는 문장을 수정한다 | PATIENT |
| PHRASE-004 | DELETE | `/api/phrases/{phraseId}` | 자주 쓰는 문장을 삭제한다 | PATIENT |
| FRIEND-001 | GET | `/api/friends` | SMS 지인 목록을 조회한다 | PATIENT |
| FRIEND-002 | POST | `/api/friends` | SMS 지인을 등록한다 | PATIENT |
| FRIEND-003 | PATCH | `/api/friends/{friendshipId}` | SMS 지인을 수정한다 | PATIENT |
| FRIEND-004 | DELETE | `/api/friends/{friendshipId}` | SMS 지인을 삭제한다 | PATIENT |
| CHAT-001 | GET | `/api/chat-contacts` | 병원 채팅 친구 탭의 내부 회원을 조회한다 | AUTH |
| CHAT-002 | GET | `/api/chat-rooms` | 현재 사용자의 생성된 채팅방 목록을 조회한다 | AUTH |
| CHAT-003 | POST | `/api/chat-rooms/hospital` | 선택한 내부 회원과 채팅방을 조회하거나 생성한다 | AUTH |
| CHAT-004 | POST | `/api/chat-rooms/sms` | 선택한 SMS 친구와 채팅방을 조회하거나 생성한다 | PATIENT |
| CHAT-005 | GET | `/api/chat-rooms/{roomId}/messages` | 현재 사용자가 복호화할 수 있는 ciphertext를 조회한다 | AUTH |
| CHAT-006 | POST | `/api/chat-rooms/{roomId}/messages` | 병원 E2EE 메시지 또는 SMS 메시지를 전송한다 | AUTH |

---

# Auth

# 17. AUTH-001 이메일 인증번호 발송

### 기능 설명

회원가입 또는 비밀번호 재설정 과정에서 사용자가 입력한 이메일의 소유 여부를 확인하기 위한 인증번호를 발급한다.

### Endpoint

```http
POST /api/auth/email-verifications
```

### Request

```json
{
  "email": "patient@example.com",
  "purpose": "SIGNUP"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `email` | String | Y | 인증할 이메일을 전달한다 |
| `purpose` | Enum | Y | `SIGNUP`, `PASSWORD_RESET` 중 하나를 전달한다 |

### 처리 방식

`purpose=SIGNUP`이면 `users.user_email` 중복 여부를 확인한다.

가입된 이메일이면 `AUTH_DUPLICATE_EMAIL`을 반환한다.

6자리 인증번호를 생성한다.

서버 비밀키를 이용해 인증번호의 HMAC-SHA256 값을 계산한다.

`verification_code`에 다음 정보를 저장한다.

```text
target_type = EMAIL
target_value = 요청 이메일
purpose = SIGNUP 또는 PASSWORD_RESET
code_hash = HMAC-SHA256(인증번호)
expires_at = 현재 시각 + 인증 유효시간
verified_at = NULL
```

인증번호 원문은 DB와 Application Log에 저장하지 않는다.

동일 이메일과 동일 purpose에 대한 이전 미사용 인증번호는 새 코드 발급 시 사용할 수 없도록 처리한다.

메일 Provider를 호출해 인증번호를 발송한다.

`PASSWORD_RESET` 요청에서는 가입 여부를 외부에 노출하지 않도록 존재하지 않는 이메일에도 동일한 성공 응답을 반환한다.

### Success

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "인증번호 발송 요청이 처리되었습니다.",
  "data": {
    "expiresInSeconds": 300
  }
}
```

---

# 18. AUTH-002 이메일 인증번호 확인

### Endpoint

```http
POST /api/auth/email-verifications/confirm
```

### Request

```json
{
  "email": "patient@example.com",
  "purpose": "SIGNUP",
  "code": "391024"
}
```

### 처리 방식

`target_type=EMAIL`, `target_value=email`, `purpose`가 일치하는 가장 최근 인증 레코드를 조회한다.

사용자가 입력한 인증번호에 동일한 HMAC-SHA256 연산을 적용해 `code_hash`와 비교한다.

만료시간을 확인한다.

정상적인 경우 `verified_at`을 현재 시각으로 변경한다.

회원가입에서는 `verified_at IS NOT NULL`인 인증 레코드를 서버에서 다시 조회해 인증 완료 여부를 판단한다.

`purpose=PASSWORD_RESET`인 경우 비밀번호 재설정용 일회성 토큰을 생성한다.

토큰 원문은 Response에 반환하고 DB에는 해시값과 만료시간만 저장한다.

### SIGNUP Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "이메일 인증이 완료되었습니다.",
  "data": {
    "verified": true
  }
}
```

### PASSWORD_RESET Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "이메일 인증이 완료되었습니다.",
  "data": {
    "resetToken": "..."
  }
}
```

---

# 19. AUTH-003 로그인 아이디 중복확인

### Endpoint

```http
GET /api/auth/login-id/availability?loginId=patient01
```

### 처리 방식

`users.login_id` 존재 여부를 조회한다.

존재하면 `available=false`, 존재하지 않으면 `available=true`를 반환한다.

중복확인 API 결과와 관계없이 회원가입 시 동일 검사를 다시 수행한다.

DB UNIQUE 제약을 최종 중복 방어 수단으로 사용한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "아이디 사용 가능 여부를 확인했습니다.",
  "data": {
    "available": true
  }
}
```

---

# 20. AUTH-004 환자 회원가입

### Endpoint

```http
POST /api/auth/patients/signup
```

### 기능 설명

환자가 로그인 아이디, 이메일, 비밀번호를 이용해 계정을 생성한다. 이름은 회원가입 시 입력하지 않는다.

### Request

```json
{
  "loginId": "patient01",
  "email": "patient@example.com",
  "password": "Password123!"
}
```

### 처리 방식

`loginId` 중복 여부를 확인한다.

이메일 중복 여부를 확인한다.

해당 이메일로 `SIGNUP` 이메일 인증을 완료했는지 확인한다.

비밀번호를 BCrypt로 해시한다.

`user_id`를 서버에서 생성한다.

다음 값으로 `users`를 생성한다.

```text
login_id = 요청 loginId
user_email = 요청 email
user_pwd = BCrypt Hash
user_name = NULL
role = PATIENT
is_email_verified = TRUE
is_sms_verified = FALSE
```

`user_setting` 기본값을 함께 생성한다.

```text
keyboard_layout = QWERTY
is_key_enlarged = FALSE
current_input_method = EYE_TRACKING
```

`users`와 `user_setting` 생성을 하나의 Transaction으로 처리한다.

### Success

```http
201 Created
```

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "loginId": "patient01",
    "role": "PATIENT"
  }
}
```

회원가입 완료 후 Frontend는 E2EE Key Pair가 없는 경우 Key Pair를 생성하고 `E2EE-001`을 호출한다.

---

# 21. AUTH-005 의료진 회원가입

### Endpoint

```http
POST /api/auth/staff/signup
```

### 기능 설명

의료진이 로그인 아이디, 이메일, 비밀번호, 병원 초대코드를 이용해 계정을 생성한다. 이름은 회원가입 시 입력하지 않는다.

### Request

```json
{
  "loginId": "doctor01",
  "email": "doctor@hospital.com",
  "password": "Password123!",
  "inviteCode": "SEOUL2026"
}
```

### 처리 방식

로그인 아이디와 이메일 중복을 확인한다.

이메일 인증 완료 여부를 확인한다.

`hospital.invite_code`로 병원을 조회한다.

비밀번호를 BCrypt로 해시한다.

`users.role=STAFF`, `users.user_name=NULL`로 사용자를 생성한다.

생성된 userId와 hospitalId로 `staff`를 생성한다.

`users`와 `staff` 생성은 하나의 Transaction으로 처리한다.

### Success

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "의료진 회원가입이 완료되었습니다.",
  "data": {
    "userId": "U100",
    "staffId": 3,
    "loginId": "doctor01",
    "role": "STAFF",
    "hospital": {
      "hospitalId": 1,
      "hospitalName": "서울OO병원"
    }
  }
}
```

회원가입 완료 후 Frontend는 E2EE Key Pair가 없는 경우 Key Pair를 생성하고 `E2EE-001`을 호출한다.

---

# 22. AUTH-006 로그인

### Endpoint

```http
POST /api/auth/login
```

### 기능 설명

PATIENT와 STAFF가 동일한 API에서 `loginId + password` 방식으로 로그인한다.

이메일, 이름, PIN은 로그인 수단으로 사용하지 않는다.

### Request

```json
{
  "loginId": "patient01",
  "password": "Password123!"
}
```

### 처리 방식

`users.login_id`로 사용자를 조회한다.

사용자가 존재하지 않거나 비밀번호가 일치하지 않으면 동일하게 `AUTH_INVALID_CREDENTIALS`를 반환한다.

비밀번호는 BCrypt `matches()`로 검증한다.

로그인 성공 시 Access Token과 Refresh Token을 생성한다.

Refresh Token 원문은 HttpOnly Cookie에 저장하고 DB에는 해시값만 저장한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "로그인되었습니다.",
  "data": {
    "accessToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "role": "PATIENT"
  }
}
```

`expiresIn`은 예시이며 실제 값은 서버 설정을 사용한다.

---

# 23. AUTH-007 Access Token 재발급

### Endpoint

```http
POST /api/auth/token/refresh
```

### 기능 설명

Access Token이 만료된 경우 HttpOnly Cookie의 Refresh Token을 이용해 새로운 Access Token을 발급한다.

### Request

Request Body를 사용하지 않는다.

### 처리 방식

Cookie에서 Refresh Token을 가져온다.

Refresh Token을 해시한 후 `refresh_token.token_hash`를 조회한다.

만료 여부와 `revoked_at` 여부를 확인한다.

정상적인 경우 기존 Refresh Token을 폐기한다.

새 Refresh Token을 생성하고 새로운 hash를 저장한다.

새 Access Token을 Response Body에 반환한다.

새 Refresh Token은 HttpOnly Cookie로 교체한다.

Refresh Token Rotation을 적용해 이미 사용한 Refresh Token을 재사용할 수 없도록 한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "토큰이 재발급되었습니다.",
  "data": {
    "accessToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresIn": 900
  }
}
```

---

# 24. AUTH-008 로그아웃

### Endpoint

```http
POST /api/auth/logout
```

### 기능 설명

현재 브라우저에 발급된 Refresh Token을 폐기하고 Refresh Cookie를 제거한다.

### 처리 방식

Refresh Cookie가 존재하면 해당 token hash를 조회한다.

존재하는 Refresh Token은 `revoked_at`을 현재 시각으로 변경한다.

Response에서 Refresh Cookie를 만료한다.

Frontend는 메모리에 보관한 Access Token을 제거한다.

이미 토큰이 없거나 폐기된 상태에서도 로그아웃 요청은 멱등적으로 성공 처리한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "로그아웃되었습니다.",
  "data": null
}
```

---

# 25. AUTH-009 비밀번호 재설정

### Endpoint

```http
POST /api/auth/password/reset
```

### Request

```json
{
  "resetToken": "...",
  "newPassword": "NewPassword123!"
}
```

### 처리 방식

전달받은 resetToken을 해시한다.

`users.reset_token`과 비교한다.

`reset_token_expiry`를 확인한다.

정상적인 경우 새 비밀번호를 BCrypt로 해시한다.

`users.user_pwd`를 변경한다.

성공 후 `reset_token`, `reset_token_expiry`를 NULL로 초기화한다.

해당 사용자의 모든 Refresh Token을 폐기한다.

E2EE 개인키는 Backend에서 관리하지 않으므로 비밀번호 재설정과 E2EE Key Pair는 별도로 처리한다.

---

# User

# 26. USER-001 내 정보 조회

### Endpoint

```http
GET /api/users/me
```

### 기능 설명

마이페이지에서 현재 로그인 사용자의 계정 정보와 이름 설정 상태를 조회한다.

PATIENT와 STAFF 모두 사용한다.

### 처리 방식

JWT에서 currentUserId를 가져온다.

`users.user_id`로 사용자를 조회한다.

`user_name`이 있으면 해당 값을 `displayName`으로 사용하고 이름이 없으면 `login_id`를 사용한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "사용자 정보를 조회했습니다.",
  "data": {
    "userId": "U001",
    "loginId": "patient01",
    "email": "patient@example.com",
    "name": null,
    "displayName": "patient01",
    "phone": null,
    "emailVerified": true,
    "smsVerified": false,
    "role": "PATIENT"
  }
}
```

---

# 27. USER-002 이름 설정·수정·삭제

### Endpoint

```http
PATCH /api/users/me
```

### 기능 설명

가입 이후 마이페이지에서 PATIENT와 STAFF가 선택적으로 이름을 설정하거나 변경한다.

이름은 로그인 식별자로 사용하지 않고 화면 표시용으로 사용한다.

### Request

```json
{
  "name": "김민준"
}
```

이름 삭제 시 다음과 같이 요청한다.

```json
{
  "name": null
}
```

### 처리 방식

현재 사용자를 조회한다.

name이 문자열이면 trim 처리한다.

trim 결과가 blank이면 NULL로 변환한다.

`users.user_name`을 변경한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "사용자 정보가 수정되었습니다.",
  "data": {
    "userId": "U001",
    "name": "김민준",
    "displayName": "김민준"
  }
}
```

---

# 28. USER-003 전화번호 인증번호 발송

### Endpoint

```http
POST /api/users/me/phone-verifications
```

### 기능 설명

PATIENT가 친구 등록과 SMS 채팅 기능을 사용하기 전에 본인 전화번호 인증번호를 요청한다.

### Request

```json
{
  "phone": "01012345678"
}
```

### 처리 방식

현재 사용자의 role이 PATIENT인지 확인한다.

전화번호를 정규화한다.

6자리 인증번호를 생성한다.

서버 비밀키로 인증번호의 HMAC-SHA256 값을 계산한다.

`verification_code`에 다음 값으로 저장한다.

```text
target_type = SMS
target_value = 정규화 전화번호
purpose = PHONE_VERIFY
code_hash = HMAC-SHA256(인증번호)
expires_at = 현재 시각 + 인증 유효시간
verified_at = NULL
```

인증번호 원문은 DB와 Application Log에 저장하지 않는다.

SMS Provider를 통해 원본 인증번호를 발송한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "인증번호가 발송되었습니다.",
  "data": {
    "expiresInSeconds": 300
  }
}
```

---

# 29. USER-004 전화번호 인증 확인

### Endpoint

```http
POST /api/users/me/phone-verifications/confirm
```

### Request

```json
{
  "phone": "01012345678",
  "code": "391827"
}
```

### 처리 방식

현재 사용자의 최신 `PHONE_VERIFY` 인증 레코드를 조회한다.

사용자가 입력한 인증번호에 동일한 HMAC-SHA256 연산을 적용해 `code_hash`와 비교한다.

만료시간을 확인한다.

인증 성공 시 다음 값을 변경한다.

```text
users.user_phone = 정규화 전화번호
users.is_sms_verified = TRUE
```

친구 등록, 친구 수정, SMS 채팅방 생성, SMS 메시지 전송에서는 `is_sms_verified=true`를 다시 확인한다.

---

# 30. USER-005 회원탈퇴

### Endpoint

```http
DELETE /api/users/me
```

### 기능 설명

PATIENT와 STAFF 모두 담당 관계 유무와 관계없이 회원탈퇴를 수행한다.

### 처리 방식

현재 사용자의 Refresh Token을 모두 폐기한다.

PATIENT이면 `users`를 삭제하고 FK 정책에 따라 사용자 종속 데이터를 정리한다.

STAFF이면 `staff_patient_assignment`를 먼저 삭제하고 `staff`와 `users`를 삭제한다.

담당 환자가 존재하더라도 탈퇴를 차단하지 않는다.

Memo, Phrase, Friendship, E2EE 공개키 등 현재 사용자 전용 데이터는 삭제한다.

`message_ciphertext` 중 탈퇴 사용자에게 전달하기 위한 ciphertext는 삭제한다.

다른 참여자의 ciphertext와 기존 message metadata는 유지한다.

`chat_room`과 해당 사용자가 참여했던 `chat_participant` 행은 삭제하지 않는다(§3.8). `users`를 삭제하기 전에,
해당 사용자의 내부 `chat_participant`를 다음과 같이 **하나의 UPDATE**로 탈퇴 상태로 전이시킨다(PATIENT/STAFF
모두 동일하게 적용).

```text
UPDATE chat_participant
SET user_id = NULL, is_withdrawn = TRUE
WHERE user_id = {탈퇴하는 userId}
```

`is_withdrawn = TRUE`만 먼저 반영하고 이후 `users` 삭제가 트리거하는 FK `ON DELETE SET NULL`이 `user_id`를
정리하도록 순서를 나누면 안 된다. PostgreSQL의 CHECK 제약(`chk_chat_participant_identity`, §3.8)은 지연되지
않고 각 statement 직후 즉시 평가되므로, `user_id`가 아직 NOT NULL인 상태에서 `is_withdrawn`만 먼저 TRUE가
되는 순간 세 허용 상태 중 어디에도 속하지 않아 그 UPDATE 자체가 CHECK 위반으로 실패한다. 따라서 반드시 위
UPDATE처럼 두 컬럼을 같은 statement에서 함께 변경해 정상 상태에서 탈퇴 상태로 직접 전이시켜야 한다. 이 UPDATE
이후 실행되는 `users` DELETE의 `ON DELETE SET NULL`은 이미 `user_id`가 NULL인 행에는 아무 영향을 주지 않는다.

이 UPDATE와 `users` 삭제는 하나의 Transaction 안에서 처리한다.

탈퇴 후 해당 `chat_participant`는 §3.8의 "탈퇴한 내부 participant" 상태(`user_id NULL`, `external_phone NULL`,
`is_withdrawn TRUE`)가 되며, CHAT-002는 이 상태의 상대를 `displayName = "탈퇴한 사용자"`로 표시한다(§53).
과거 메시지의 탈퇴 사용자 표시도 동일하게 `탈퇴한 사용자`로 반환한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "회원탈퇴가 완료되었습니다.",
  "data": null
}
```

---

# UserSetting

# 31. SET-001 사용자 입력 설정 조회

```http
GET /api/users/me/settings
```

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "사용자 설정을 조회했습니다.",
  "data": {
    "keyboardLayout": "QWERTY",
    "keyEnlarged": false,
    "currentInputMethod": "EYE_TRACKING"
  }
}
```

---

# 32. SET-002 사용자 입력 설정 수정

```http
PATCH /api/users/me/settings
```

### Request

```json
{
  "keyEnlarged": true,
  "currentInputMethod": "BLINK"
}
```

PATCH 요청에 포함된 필드만 변경한다.

현재 입력 방식은 다음 값을 사용한다.

```text
EYE_TRACKING
BLINK
MOUTH
```

---

# Staff

# 33. STAFF-001 의료진 정보 조회

```http
GET /api/staff/me
```

### 기능 설명

현재 로그인 의료진의 사용자 정보와 소속 병원을 조회한다.

### 처리 방식

JWT userId로 `users`와 `staff`를 조회한다.

`staff.hospital_id`로 병원 정보를 조회한다.

이름이 없으면 `loginId`를 `displayName`으로 반환한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "의료진 정보를 조회했습니다.",
  "data": {
    "staffId": 3,
    "userId": "U100",
    "loginId": "doctor01",
    "email": "doctor@hospital.com",
    "name": null,
    "displayName": "doctor01",
    "hospital": {
      "hospitalId": 1,
      "hospitalName": "서울OO병원"
    }
  }
}
```

---

# 34. STAFF-002 담당 환자 목록 조회

```http
GET /api/staff/me/patients
```

### 기능 설명

현재 로그인 의료진에게 연결된 담당 환자 관계를 조회한다.

담당 환자 관계는 의료진의 환자 관리 기능에 사용한다. 병원 채팅 상대 목록이나 병원 채팅 접근 권한에는 사용하지 않는다.

### 처리 방식

현재 사용자 ID로 `staff`를 조회한다.

`staff_patient_assignment.staff_id`로 assignment를 조회한다.

`patient_user_id`와 `users`를 Join한다.

환자 이름이 있으면 이름을 표시하고 없으면 userId를 표시한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "담당 환자를 조회했습니다.",
  "data": [
    {
      "userId": "P001",
      "loginId": "patient01",
      "name": null,
      "displayName": "patient01",
      "assignedAt": "2026-08-08T04:00:00Z"
    }
  ]
}
```

---

# 35. STAFF-003 담당 환자 추가

```http
POST /api/staff/me/patients
```

### Request

```json
{
  "patientLoginId": "patient01"
}
```

### 기능 설명

현재 의료진과 지정한 PATIENT 사용자 사이에 담당 관계를 생성한다.

### 처리 방식

현재 사용자가 STAFF인지 확인한다.

`patientLoginId`로 users를 조회한다.

대상 사용자 role이 PATIENT인지 확인한다.

동일 `(staff_id, patient_user_id)` 관계가 이미 존재하는지 확인한다.

존재하지 않으면 `staff_patient_assignment`를 생성한다.

### Success

```http
201 Created
```

---

# 36. STAFF-004 담당 환자 해제

```http
DELETE /api/staff/me/patients/{patientUserId}
```

### 처리 방식

현재 `staff_id`와 `patientUserId` 조합의 assignment를 조회한다.

존재하면 `staff_patient_assignment`만 삭제한다.

병원 채팅방과 메시지는 변경하지 않는다.

담당 관계 해제 여부는 병원 채팅 사용 권한에 영향을 주지 않는다.

---

# E2EE

# 37. E2EE-001 공개키 등록

```http
POST /api/e2ee/keys
```

### 기능 설명

Frontend에서 생성한 현재 사용자의 E2EE 공개키를 서버에 등록한다.

개인키는 Request에 포함하지 않는다.

### Request

```json
{
  "publicKey": "BASE64_PUBLIC_KEY"
}
```

`keyVersion`은 클라이언트가 지정하지 않는다. 서버가 결정한다.

### 처리 방식

JWT에서 currentUserId를 조회한다.

현재 사용자의 등록된 키 버전(폐기된 키 포함) 중 최댓값 다음 번호로 `key_version`을 서버가 계산한다. 최초 등록이면 1이다.

동시에 같은 사용자의 등록 요청이 들어와도 동일한 `key_version`을 계산하지 않도록 사용자 단위로 동시성을 제어한다. `(user_id, key_version)` UNIQUE 제약은 최종 방어선으로 유지한다.

공개키 형식(Base64)과 디코딩 결과가 LIBSODIUM_SEALED_BOX 공개키 길이(32바이트)인지 검증한다.

`user_e2ee_key`에 공개키를 저장한다.

개인키는 Backend에서 생성하거나 저장하지 않는다.

### Success

```http
201 Created
```

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "암호화 공개키가 등록되었습니다.",
  "data": {
    "keyVersion": 1
  }
}
```

---

# 38. E2EE-002 내 공개키 등록 상태 조회

```http
GET /api/e2ee/keys/me
```

### 기능 설명

Frontend가 현재 로그인 사용자의 공개키 등록 여부와 현재 keyVersion을 확인한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "암호화 키 정보를 조회했습니다.",
  "data": {
    "registered": true,
    "keyVersion": 1,
    "publicKey": "BASE64_PUBLIC_KEY"
  }
}
```

---

# 39. E2EE-003 사용자 공개키 조회

```http
GET /api/e2ee/users/{userId}/public-key
```

### 기능 설명

병원 채팅 메시지를 암호화하기 위해 상대 내부 회원의 현재 공개키를 조회한다.

### 처리 방식

대상 사용자가 존재하는지 확인한다.

대상 사용자의 최신 활성 공개키를 조회한다.

공개키가 없으면 `E2EE_TARGET_KEY_REQUIRED`를 반환한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "사용자 공개키를 조회했습니다.",
  "data": {
    "userId": "U100",
    "keyVersion": 1,
    "publicKey": "BASE64_PUBLIC_KEY"
  }
}
```

---

# Memo

# 40. MEMO-001 메모 조회

```http
GET /api/memos
```

### 기능 설명

현재 로그인 환자의 암호화된 개인 메모 목록을 조회한다.

Backend는 메모 평문을 복호화하지 않는다.

### 처리 방식

`memo.user_id=currentUserId`인 데이터만 조회한다.

최근 수정 순으로 정렬한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "메모를 조회했습니다.",
  "data": [
    {
      "memoId": 12,
      "encryptedPayload": {
        "algorithm": "LIBSODIUM_SEALED_BOX",
        "keyVersion": 1,
        "ciphertext": "BASE64_CIPHERTEXT"
      },
      "createdAt": "...",
      "updatedAt": "..."
    }
  ]
}
```

Frontend가 사용자 개인키로 ciphertext를 복호화한다.

---

# 41. MEMO-002 메모 등록

```http
POST /api/memos
```

### Request

```json
{
  "encryptedPayload": {
    "algorithm": "LIBSODIUM_SEALED_BOX",
    "keyVersion": 1,
    "ciphertext": "BASE64_CIPHERTEXT"
  }
}
```

### 처리 방식

현재 사용자의 E2EE 공개키 등록 여부를 확인한다.

Request의 keyVersion이 현재 사용자의 등록된 Key Version인지 확인한다.

ciphertext 형식과 최대 크기를 검증한다.

`memo.user_id`, ciphertext, keyVersion을 저장한다.

Backend에서는 ciphertext를 복호화하지 않는다.

### Success

```http
201 Created
```

---

# 42. MEMO-003 메모 수정

```http
PATCH /api/memos/{memoId}
```

### Request

```json
{
  "encryptedPayload": {
    "algorithm": "LIBSODIUM_SEALED_BOX",
    "keyVersion": 1,
    "ciphertext": "BASE64_CIPHERTEXT"
  }
}
```

### 처리 방식

현재 사용자의 E2EE 공개키 등록 여부를 확인한다.

`memo_id`와 currentUserId를 동시에 조건으로 조회한다.

본인 메모가 아니거나 존재하지 않으면 `MEMO_NOT_FOUND`를 반환한다.

새 ciphertext와 keyVersion으로 교체한다.

---

# 43. MEMO-004 메모 삭제

```http
DELETE /api/memos/{memoId}
```

현재 사용자 소유의 메모인지 확인한 후 삭제한다.

---

# Phrase

# 44. PHRASE-001 자주 쓰는 문장 조회

```http
GET /api/phrases
```

### 기능 설명

마이페이지에서 현재 사용자가 등록한 자주 쓰는 문장을 조회한다.

자주 쓰는 문장은 최대 3개를 유지한다.

평문은 Frontend에서 복호화한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "자주 쓰는 문장을 조회했습니다.",
  "data": [
    {
      "phraseId": 10,
      "category": null,
      "encryptedPayload": {
        "algorithm": "LIBSODIUM_SEALED_BOX",
        "keyVersion": 1,
        "ciphertext": "BASE64_CIPHERTEXT"
      },
      "createdAt": "..."
    }
  ]
}
```

---

# 45. PHRASE-002 자주 쓰는 문장 등록

```http
POST /api/phrases
```

### 기능 설명

마이페이지의 자주 쓰는 문장 관리 화면에서 새 문장을 등록한다.

현재 사용자의 문장이 이미 3개인 경우 가장 오래된 문장을 삭제하고 새 문장을 저장해 항상 최대 3개를 유지한다.

### Request

```json
{
  "category": null,
  "encryptedPayload": {
    "algorithm": "LIBSODIUM_SEALED_BOX",
    "keyVersion": 1,
    "ciphertext": "BASE64_CIPHERTEXT"
  }
}
```

### 처리 방식

현재 사용자의 E2EE 키 등록 여부를 확인한다.

현재 사용자 기준으로 Phrase 레코드 수를 확인한다.

0~2개이면 새 Phrase를 저장한다.

3개이면 `created_at ASC` 기준 가장 오래된 Phrase를 조회해 삭제한다.

삭제 후 새 Phrase를 저장한다.

동시 등록으로 3개를 초과하지 않도록 사용자 또는 Phrase 목록에 Write Lock을 적용하고 전체 처리를 하나의 Transaction으로 수행한다.

```text
0개 → INSERT
1개 → INSERT
2개 → INSERT
3개 → 가장 오래된 Phrase DELETE → 신규 Phrase INSERT
```

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "자주 쓰는 문장이 등록되었습니다.",
  "data": {
    "phraseId": 10,
    "replacedPhraseId": 3,
    "createdAt": "..."
  }
}
```

교체된 문장이 없으면 `replacedPhraseId=null`을 반환한다.

---

# 46. PHRASE-003 자주 쓰는 문장 수정

```http
PATCH /api/phrases/{phraseId}
```

### Request

```json
{
  "category": null,
  "encryptedPayload": {
    "algorithm": "LIBSODIUM_SEALED_BOX",
    "keyVersion": 1,
    "ciphertext": "BASE64_CIPHERTEXT"
  }
}
```

### 처리 방식

현재 사용자의 E2EE 공개키 등록 여부를 확인한다.

현재 사용자 소유 Phrase인지 확인한다.

Request의 keyVersion이 현재 사용자의 등록된 공개키 버전인지 확인한다.

ciphertext와 category를 수정한다.

수정 시 `created_at`은 변경하지 않는다. 자동 교체 기준은 최초 생성시각을 사용한다.

---

# 47. PHRASE-004 자주 쓰는 문장 삭제

```http
DELETE /api/phrases/{phraseId}
```

현재 사용자 소유 Phrase인지 확인한 후 삭제한다.

자동 FIFO 교체 기능과 별개로 사용자가 마이페이지에서 직접 문장을 제거할 수 있도록 삭제 API를 유지한다.

---

# Friend

# 48. FRIEND-001 SMS 지인 목록 조회

```http
GET /api/friends
```

### 기능 설명

현재 환자가 등록한 외부 SMS 연락처를 조회한다.

친구 등록이 완료되면 `friendship`에 저장되므로 별도의 친구 탭 등록 API 없이 이 API 결과에 자동 포함된다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "친구 목록을 조회했습니다.",
  "data": [
    {
      "friendshipId": 7,
      "name": "엄마",
      "phone": "01012345678"
    }
  ]
}
```

---

# 49. FRIEND-002 SMS 지인 등록

```http
POST /api/friends
```

### 기능 설명

전화번호 인증이 완료된 PATIENT가 이름과 전화번호로 외부 SMS 지인을 등록한다.

### Request

```json
{
  "name": "엄마",
  "phone": "01012345678"
}
```

`name`, `phone` 모두 필수다.

### 처리 방식

현재 사용자가 PATIENT인지 확인한다.

`users.is_sms_verified=true`인지 확인한다.

친구 전화번호를 정규화한다.

정규화 결과가 빈 값(숫자를 하나도 포함하지 않는 입력, 예: `"abcdef"`, `"---"`)이면 `COMMON_VALIDATION_ERROR`로 거부한다.

동일 `(user_id, friend_phone)`이 이미 존재하는지 확인한다.

다음 정보로 friendship을 생성한다.

```text
user_id = currentUserId
friend_user_id = NULL
friend_name = 요청 name
friend_phone = 정규화 전화번호
status = ACCEPTED
```

친구 등록 시 SMS 채팅방은 생성하지 않는다.

### Success

```http
201 Created
```

---

# 50. FRIEND-003 SMS 지인 수정

```http
PATCH /api/friends/{friendshipId}
```

### Request

```json
{ "name": "어머니" }
```

또는

```json
{ "phone": "01099998888" }
```

또는 두 필드를 함께 전달할 수도 있다.

`name`, `phone`은 각각 선택(optional)이며, 최소 하나는 전달해야 한다. 이 API는 partial update로 동작하며, 전달한
필드만 수정하고 전달하지 않은 필드는 기존 값을 그대로 유지한다.

### 처리 방식

PATIENT인지 확인한다.

전화번호 인증 완료 여부를 확인한다.

현재 사용자 소유 friendship인지 확인한다. 없거나 다른 사용자 소유면 `FRIEND_NOT_FOUND`를 반환해 존재 여부를
노출하지 않는다.

`name`, `phone` 둘 다 전달되지 않았으면 `COMMON_VALIDATION_ERROR`로 거부한다.

`name`이 전달됐고 빈 값(공백만)이면 `COMMON_VALIDATION_ERROR`로 거부한다. 전달된 경우에만 친구 이름을 변경한다.

`phone`이 전달됐으면 정규화한다. 정규화 결과가 빈 값이면 `COMMON_VALIDATION_ERROR`로 거부한다. 값이 실제로
바뀌는 경우에만 동일 사용자의 다른 friendship과 중복 여부를 다시 확인하고(중복 시 `FRIEND_ALREADY_EXISTS`),
전달된 경우에만 전화번호를 변경한다.

---

# 51. FRIEND-004 SMS 지인 삭제

```http
DELETE /api/friends/{friendshipId}
```

현재 사용자 소유 friendship인지 확인한 후 friendship만 삭제한다.

기존 SMS 채팅방과 암호화된 메시지 이력은 유지한다.

---

# Chat

# 52. CHAT-001 병원 채팅 연락처 조회

```http
GET /api/chat-contacts?page=0&size=20&keyword=
```

### 기능 설명

병원 채팅의 친구 탭에 표시할 Look Talk 내부 회원 목록을 조회한다.

현재는 병원 소속, 담당 환자 관계, role 조합으로 채팅 대상을 제한하지 않는다.

PATIENT와 STAFF를 모두 조회한다.

현재 로그인 사용자는 목록에서 제외한다.

### 처리 방식

users를 조회한다.

keyword가 있으면 `user_name` 또는 `login_id` 기준으로 검색한다.

이메일은 채팅 연락처 Response에 노출하지 않는다. 이름이 없는 사용자는 `displayName`을 통해 `login_id`가 표시된다.

각 사용자의 E2EE 공개키 등록 여부를 조회해 `e2eeReady`를 반환한다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "채팅 상대 목록을 조회했습니다.",
  "data": {
    "content": [
      {
        "userId": "U001",
        "role": "PATIENT",
        "name": null,
        "displayName": "patient01",
        "e2eeReady": true
      },
      {
        "userId": "U100",
        "role": "STAFF",
        "name": "김철수",
        "displayName": "김철수",
        "e2eeReady": true
      }
    ],
    "hasNext": false
  }
}
```

개인적으로 서비스를 사용하는 PATIENT도 동일하게 목록에 포함한다.

---

# 53. CHAT-002 채팅방 목록 조회

```http
GET /api/chat-rooms
```

선택적으로 room type을 필터링한다.

```http
GET /api/chat-rooms?type=HOSPITAL
GET /api/chat-rooms?type=SMS
```

### 기능 설명

현재 사용자가 participant로 포함된 이미 생성된 채팅방을 조회한다.

내부 회원 전체 목록은 `CHAT-001`에서 조회하고, 이 API는 실제 생성된 대화방 이력을 조회하는 용도로 사용한다.

### 처리 방식

현재 userId가 포함된 `chat_participant`를 조회한다.

연결된 room과 상대 participant를 조회한다.

가장 최근 message의 현재 사용자용 ciphertext를 함께 조회한다.

평문 마지막 메시지는 Backend에서 생성하지 않는다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "채팅방을 조회했습니다.",
  "data": [
    {
      "roomId": 10,
      "roomType": "HOSPITAL",
      "target": {
        "type": "USER",
        "userId": "U100",
        "name": "김철수",
        "displayName": "김철수"
      },
      "lastMessageEncryptedPayload": {
        "algorithm": "LIBSODIUM_SEALED_BOX",
        "keyVersion": 1,
        "ciphertext": "BASE64_CIPHERTEXT"
      },
      "lastMessageAt": "..."
    }
  ]
}
```

Frontend는 ciphertext를 복호화해 마지막 메시지 미리보기를 표시한다.

### target (SMS room)

`roomType=SMS`인 room의 `target`은 회원이 아닌 외부 지인이므로 `userId`/`displayName`이 없다.

`target.name`/`target.phone`은 **friendship을 실시간 조회한 값이 아니라**, CHAT-004로 room을 생성하던 시점에
`chat_participant.external_name`/`external_phone`에 저장된 snapshot을 그대로 반환한다. 이후 FRIEND-003으로
friendship의 이름/전화번호가 수정되거나 FRIEND-004로 friendship이 삭제되어도, 이미 생성된 room의 `target`
표시는 변경되지 않는다(CHAT-002 구현이 friendship 테이블을 다시 조회하지 않기 때문).

```json
{
  "roomId": 11,
  "roomType": "SMS",
  "target": {
    "type": "SMS",
    "name": "엄마",
    "phone": "01012345678"
  },
  "lastMessageEncryptedPayload": null,
  "lastMessageAt": null
}
```

HOSPITAL room의 `target.type`은 `USER`, SMS room의 `target.type`은 `SMS`다. 두 경우 모두 같은 JSON 구조를
공유하되 해당 없는 필드(`USER`의 `phone`, `SMS`의 `userId`/`displayName`)는 `null`로 채운다.

### target (탈퇴한 HOSPITAL 상대)

HOSPITAL room의 상대가 회원탈퇴(USER-005)한 경우에도 room/message 이력은 삭제하지 않고 그대로 유지한다.
이때 `target`은 `CHAT-005`의 기존 탈퇴 사용자 표시 정책(`senderDisplayName="탈퇴한 사용자"`)과 동일하게
다음과 같이 반환한다. 탈퇴 전의 `userId`/`loginId`/`name`은 별도로 복구하거나 snapshot으로 남기지 않는다.

```json
{
  "type": "USER",
  "userId": null,
  "name": null,
  "displayName": "탈퇴한 사용자",
  "phone": null
}
```

### 메시지가 없는 room

CHAT-006(메시지 전송)을 아직 구현하지 않았으므로 message가 하나도 없는 신규 room도 이 목록에 정상적으로
포함되어야 한다. 이 경우:

```text
lastMessageEncryptedPayload = null
lastMessageAt = null
```

message는 존재하지만 현재 사용자용 `message_ciphertext`가 없는 경우(데이터 무결성 문제로만 발생 가능한 상태)도
동일하게 `lastMessageEncryptedPayload = null`, `lastMessageAt = null`로 처리한다. 평문 fallback이나 다른
사용자의 ciphertext를 대신 반환하지 않는다.

### 정렬 기준

room은 "가장 최근 활동 시각" 기준 내림차순으로 정렬한다. 가장 최근 활동 시각은 다음과 같이 정의한다.

```text
현재 사용자용 ciphertext가 있는 최신 message가 있으면 -> 그 message.created_at
없으면(신규 room 또는 위 데이터 무결성 예외 상태) -> room.created_at
```

---

# 54. CHAT-003 병원 채팅방 조회·생성

```http
POST /api/chat-rooms/hospital
```

### 기능 설명

병원 채팅 친구 탭에서 선택한 내부 회원과의 기존 1:1 채팅방을 반환하거나 새 채팅방을 생성한다.

다음 role 조합을 모두 허용한다.

```text
PATIENT ↔ PATIENT
PATIENT ↔ STAFF
STAFF ↔ PATIENT
STAFF ↔ STAFF
```

### Request

```json
{
  "targetUserId": "U100"
}
```

### 처리 방식

현재 사용자와 target 사용자를 조회한다.

target이 존재하지 않으면 `CHAT_TARGET_NOT_FOUND`를 반환한다.

자기 자신이면 `CHAT_SELF_TARGET`을 반환한다.

두 사용자의 기존 HOSPITAL 1:1 room이 있는지 조회한다.

기존 room이 있으면 `200 OK`로 반환한다.

없으면 `chat_room(room_type=HOSPITAL)`을 생성한다.

현재 사용자와 target 사용자를 각각 `chat_participant`로 생성한다.

room과 participant 생성을 하나의 Transaction으로 처리한다.

`staff_patient_assignment`와 hospital 소속은 검사하지 않는다.

### Success

기존 room이면 `200 OK`, 신규 room이면 `201 Created`를 반환한다.

### Response

`CHAT-002`의 room 목록 항목과 혼동되지 않도록, find-or-create 응답은 방금 조회/생성된 room 자체를 가리키는
데 필요한 최소 정보만 반환한다(상대방 정보/마지막 메시지는 포함하지 않는다). 이후 상대방 표시나 대화 내용이
필요하면 `CHAT-002`/`CHAT-005`를 사용한다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "채팅방을 조회했습니다.",
  "data": {
    "roomId": 10,
    "roomType": "HOSPITAL",
    "createdAt": "..."
  }
}
```

기존 room을 반환하는 경우도 동일한 구조를 사용하며, `createdAt`은 room이 처음 생성된 시각이다(이번 조회
시각이 아니다).

---

# 55. CHAT-004 SMS 채팅방 조회·생성

```http
POST /api/chat-rooms/sms
```

### 기능 설명

친구 채팅 탭에서 등록된 외부 친구를 선택했을 때 기존 SMS 채팅방을 반환하거나 새 채팅방을 생성한다.

### Request

```json
{
  "friendshipId": 7
}
```

### 처리 방식

현재 사용자가 PATIENT인지 확인한다.

`is_sms_verified=true`인지 확인한다.

현재 사용자의 E2EE 공개키가 등록되어 있는지 확인한다. 공개키가 없으면 `E2EE_KEY_REQUIRED`를 반환한다.

해당 friendship이 현재 사용자의 데이터인지 확인한다.

같은 현재 사용자와 같은 friend phone으로 만들어진 SMS room을 조회한다.

기존 room이 있으면 반환한다.

없으면 `chat_room(room_type=SMS)`을 생성한다.

환자 participant를 생성한다.

```text
user_id = currentUserId
external_name = NULL
external_phone = NULL
```

외부 친구 participant를 생성한다.

```text
user_id = NULL
external_name = friendship.friend_name
external_phone = friendship.friend_phone
```

room과 participant 생성을 하나의 Transaction으로 처리한다.

### Success

`CHAT-003`과 동일한 find-or-create semantics를 따른다: 기존 room이면 `200 OK`, 신규 room이면 `201 Created`를
반환한다.

### Response

`CHAT-003`과 동일한 최소 room 응답 구조를 사용한다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "채팅방을 조회했습니다.",
  "data": {
    "roomId": 11,
    "roomType": "SMS",
    "createdAt": "..."
  }
}
```

---

# 56. CHAT-005 메시지 조회

```http
GET /api/chat-rooms/{roomId}/messages?size=50
```

이전 메시지는 cursor를 사용해 조회한다.

```http
GET /api/chat-rooms/{roomId}/messages?beforeMessageId=300&size=50
```

### 기능 설명

현재 사용자가 참여 중인 채팅방의 메시지 이력을 조회한다.

Backend는 현재 사용자가 복호화할 수 있는 ciphertext만 반환한다.

### 처리 방식

roomId로 `chat_room`을 조회한다. 없으면 `CHAT_ROOM_NOT_FOUND`(404)를 반환한다.

현재 사용자가 해당 room의 내부 `chat_participant`인지 확인한다. room은 존재하지만 participant가 아니면
`CHAT_ACCESS_DENIED`(403)를 반환한다(존재 여부를 `CHAT_ROOM_NOT_FOUND`로 숨기지 않는다). hospital 소속이나
`staff_patient_assignment`는 검사하지 않는다.

`beforeMessageId`가 있으면 `message_id < beforeMessageId` 조건으로 조회한다.

각 message에 대해 `message_ciphertext.recipient_user_id=currentUserId`인 ciphertext를 조회한다.

외부 SMS 상대가 보낸 메시지도 환자 공개키로 암호화된 ciphertext를 반환한다.

CHAT-005는 현재 사용자의 active E2EE key 등록 여부를 선행조건으로 요구하지 않는다(`E2EE_KEY_REQUIRED`/
`E2EE_TARGET_KEY_REQUIRED` 검사를 하지 않는다). message는 저장 당시 recipient의 keyVersion으로 암호화되어
있으므로, 현재 active keyVersion이 바뀌었거나 그 keyVersion이 이후 revoke되었더라도 `message_ciphertext`에
저장된 `algorithm`/`keyVersion`/`ciphertext`를 그대로 반환한다. 재암호화하거나 현재 active keyVersion으로
바꾸지 않는다. Frontend가 과거 개인키로 복호화하는 구조를 전제로 한다.

### 정렬 및 cursor pagination

메시지는 `messageId` 내림차순(최신 → 과거)으로 정렬한다.

`size` 기본값은 50이며 1~100 범위만 허용한다. 범위를 벗어나면 `COMMON_VALIDATION_ERROR`(400)를 반환한다.

`beforeMessageId`는 양수만 허용한다. 0 이하이면 `COMMON_VALIDATION_ERROR`(400)를 반환한다.

`hasNext`는 COUNT 쿼리 없이 `size + 1`건을 조회해 판단한다(`size + 1`번째 결과가 있으면 `true`).

`hasNext = true`이면 `nextCursor`는 이번 응답에 포함된 메시지 중 가장 오래된(가장 작은) `messageId`다.
`hasNext = false`이면 `nextCursor = null`이다.

pagination 대상은 **현재 사용자의 `message_ciphertext`가 존재하는 message**다. 즉 `roomId`가 일치하고
`message_ciphertext.recipient_user_id = currentUserId`인 message/ciphertext 쌍을 기준으로 cursor pagination을
수행한다. 현재 사용자용 ciphertext가 없는 비정상 message(데이터 무결성 문제로만 발생 가능한 상태)는 목록에서
아예 제외하며, 다른 recipient의 ciphertext로 대체하지 않는다. 이렇게 해야 `size`만큼 message metadata를 먼저
조회한 뒤 ciphertext 없는 행을 걸러내면서 실제 반환 건수나 `hasNext`/`nextCursor`가 어긋나는 문제가 생기지
않는다.

예시(DB에 `messageId` 500, 499, 498, 497이 있고 모두 현재 사용자용 ciphertext가 있는 경우):

```text
GET .../messages?size=2
-> messages: [500, 499], hasNext: true, nextCursor: 499

GET .../messages?beforeMessageId=499&size=2
-> messages: [498, 497], hasNext: false, nextCursor: null
```

같은 message가 페이지 사이에서 중복 반환되지 않는다.

### sender 표시

`senderParticipantId`는 `message.sender_participant_id`다. sender participant의 상태(§3.8)에 따라 다음
세 가지로 반환한다.

```text
정상 내부 사용자 (user != null):
  senderUserId = user.userId
  senderDisplayName = DisplayNameResolver.resolve(user)  (user_name 없으면 login_id)

탈퇴한 내부 사용자 (user == null && isWithdrawn == true):
  senderUserId = null
  senderDisplayName = "탈퇴한 사용자"

외부 SMS 상대 (user == null && isWithdrawn == false && externalPhone != null):
  senderUserId = null
  senderDisplayName = chat_participant.external_name  (SMS room 생성 당시 저장한 snapshot)
```

외부 SMS sender는 friendship을 다시 조회하지 않고 `chat_participant.external_name` snapshot을 그대로
사용한다(§53 SMS target snapshot 정책과 동일). friendship이 이후 삭제되어도 표시가 바뀌지 않는다. 외부
SMS sender를 "탈퇴한 사용자"로 표시하지 않는다.

### Response

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "메시지를 조회했습니다.",
  "data": {
    "messages": [
      {
        "messageId": 299,
        "senderParticipantId": 4,
        "senderUserId": "U100",
        "senderDisplayName": "김철수",
        "encryptedPayload": {
          "algorithm": "LIBSODIUM_SEALED_BOX",
          "keyVersion": 1,
          "ciphertext": "BASE64_CIPHERTEXT"
        },
        "messageType": "TEXT",
        "createdAt": "..."
      }
    ],
    "hasNext": true,
    "nextCursor": 250
  }
}
```

탈퇴한 내부 사용자의 경우 `senderUserId=null`, `senderDisplayName="탈퇴한 사용자"`로 반환한다.

---

# 57. CHAT-006 메시지 전송

```http
POST /api/chat-rooms/{roomId}/messages
```

room type에 따라 HOSPITAL과 SMS 요청을 구분한다.

### 공통 처리 방식

roomId로 `chat_room`을 조회한다. 없으면 `CHAT_ROOM_NOT_FOUND`(404)를 반환한다.

현재 사용자가 해당 room의 내부 `chat_participant`인지 확인한다. room은 존재하지만 participant가 아니면
`CHAT_ACCESS_DENIED`(403)를 반환한다. `message.sender_participant_id`는 이 participant의 ID를 사용한다.
hospital 소속과 `staff_patient_assignment`는 검사하지 않는다.

이번 버전에서 지원하는 `messageType`은 `TEXT` 하나뿐이다. 다른 값은 `COMMON_VALIDATION_ERROR`(400)로 거부한다.

Request는 하나의 endpoint를 공유하지만 `roomType`에 따라 필드 사용이 엄격히 갈린다.

```text
HOSPITAL: ciphertexts 필수, content는 사용 금지(값이 있으면 COMMON_VALIDATION_ERROR 400)
SMS     : content 필수, ciphertexts는 사용 금지(값이 있으면 COMMON_VALIDATION_ERROR 400)
```

### Success

HOSPITAL/SMS 모두 동일한 Response 구조와 `200 OK`를 사용한다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "메시지가 전송되었습니다.",
  "data": {
    "messageId": 320,
    "createdAt": "..."
  }
}
```

ciphertext는 Response에 다시 포함하지 않는다. 전송한 메시지를 확인해야 하면 `CHAT-005`로 조회한다.

## 57.1 HOSPITAL 메시지

### Request

```json
{
  "messageType": "TEXT",
  "ciphertexts": [
    {
      "recipientUserId": "U001",
      "keyVersion": 1,
      "algorithm": "LIBSODIUM_SEALED_BOX",
      "ciphertext": "BASE64_FOR_U001"
    },
    {
      "recipientUserId": "U100",
      "keyVersion": 1,
      "algorithm": "LIBSODIUM_SEALED_BOX",
      "ciphertext": "BASE64_FOR_U100"
    }
  ]
}
```

### 처리 방식

room type이 HOSPITAL인지 확인한다.

현재 사용자의 E2EE 공개키가 등록되어 있는지 확인한다. 없으면 `E2EE_KEY_REQUIRED`(409)를 반환한다.

room의 상대 내부 participant가 회원탈퇴(§3.8 `is_withdrawn=true`, `user=null`) 상태이면 더 이상 새 메시지를
전송할 수 없다. 탈퇴 전 과거 이력은 `CHAT-005`로 계속 조회할 수 있지만, 탈퇴한 상대가 있는 room에는 새로
전송하지 않는다. 이 경우 `CHAT_TARGET_NOT_FOUND`(404)를 반환한다(E2EE-003에서 "대상이 존재하지 않음"에 이미
쓰이는 코드로, "더 이상 유효한 내부 채팅 상대가 없다"는 의미를 그대로 재사용할 수 있다).

room의 활성 내부 participant(탈퇴하지 않은 회원) 전원이 E2EE 공개키를 등록했는지 확인한다. 하나라도
등록되지 않았다면 `E2EE_TARGET_KEY_REQUIRED`(409)를 반환하고 메시지 전송을 중단한다.

room의 활성 내부 participant userId 집합을 구한다(1:1 room이므로 현재 사용자 자신을 포함해 정확히 2명).

Request `ciphertexts[].recipientUserId` 집합이 이 participant userId 집합과 **정확히 일치**해야 한다. 다음은
모두 `E2EE_INVALID_PAYLOAD`(400)로 거부한다.

```text
- sender 자신의 ciphertext 누락 (보낸 사람도 CHAT-005에서 자기 메시지를 복호화할 수 있어야 하므로 필수)
- 상대 ciphertext 누락
- room participant가 아닌 제3자 recipientUserId
- 동일 recipientUserId 중복(Set 변환으로 조용히 무시하지 않고 명시적으로 거부)
- ciphertexts 개수가 participant 수와 다름
```

각 ciphertext 항목에 대해:

```text
algorithm: 정확히 "LIBSODIUM_SEALED_BOX"가 아니면 E2EE_INVALID_PAYLOAD(400)
ciphertext: RFC 4648 Standard Base64로 decode 불가능하면 E2EE_INVALID_PAYLOAD(400)
keyVersion: 해당 recipientUserId의 현재 active 공개키 버전과 정확히 일치해야 한다.
            일치하지 않으면(다른 사용자 버전, 존재하지 않는 버전, revoke된 과거 버전 모두 포함)
            E2EE_KEY_NOT_FOUND(404) - Memo/Phrase 신규 저장 시 keyVersion 검증과 동일한 semantics.
```

새 메시지는 항상 recipient의 **현재 active keyVersion**만 허용한다. `CHAT-005` 조회에서 과거 revoked
keyVersion ciphertext를 그대로 반환하는 것과 이 정책을 혼동하지 않는다 - GET은 과거 이력을 그대로 보여주고,
POST(신규 전송)는 현재 유효한 키로만 암호화된 ciphertext만 받는다.

Backend는 어떤 ciphertext도 복호화하지 않는다. Request ciphertext의 Base64 decode는 형식 검증 목적일 뿐이며
평문을 얻는 과정이 아니다.

모든 검증을 통과하면 `message` metadata 1건과 recipient별 `message_ciphertext`(1:1 room이므로 정확히 2건)를
하나의 Transaction으로 저장한다. 하나라도 검증에 실패하면 `message`/`message_ciphertext` 어느 것도 저장되지
않는다(원자적 저장). `message.content` 같은 평문 컬럼은 만들지 않는다.

담당 환자 관계와 병원 소속은 검사하지 않는다.

저장 commit 이후 사용자별 WebSocket 이벤트를 발행한다(§58, 다음 단계에서 연결).

## 57.2 SMS 메시지

### Request

```json
{
  "messageType": "TEXT",
  "content": "오늘 저녁에 전화해주세요"
}
```

### 처리 방식

room type이 SMS인지 확인한다.

현재 사용자가 PATIENT인지 확인한다. STAFF면 `COMMON_FORBIDDEN`(403)을 반환한다(Security URL 규칙과 별개로
Service에서도 검사한다).

전화번호 인증 완료 여부를 확인한다. 미인증이면 `SMS_VERIFICATION_REQUIRED`(403)를 반환한다.

현재 사용자의 E2EE 공개키가 등록되어 있는지 확인한다. 공개키가 없으면 `E2EE_KEY_REQUIRED`(409)를 반환한다.

외부 participant의 전화번호를 조회한다. 이때 `friendship`을 다시 조회하지 않고 `CHAT-004`로 room을 생성할
당시 `chat_participant.external_phone`에 저장된 snapshot을 그대로 사용한다(§53 SMS target snapshot 정책과
동일). `FRIEND-004`로 friendship이 이후 삭제되었더라도 이미 생성된 SMS room에서는 계속 전송할 수 있다.

SMS Provider에 평문 content를 전송한다. 이 Provider 호출은 DB write Transaction 밖에서 수행해, 외부 네트워크
호출 동안 DB Transaction을 길게 붙잡지 않는다.

```text
Provider 미설정: SMS_PROVIDER_NOT_CONFIGURED(503), message/message_ciphertext 저장하지 않음
Provider 발송 실패: SMS_SEND_FAILED(502), message/message_ciphertext 저장하지 않음
```

Provider가 발송 요청을 정상 수락한 경우에만 현재 사용자의 **현재 active 공개키**로 content를 sealed box
암호화한다(HOSPITAL과 동일하게 신규 저장은 항상 현재 active keyVersion을 사용하며, key rotation 이후에도
과거 메시지의 keyVersion은 재암호화하지 않는다).

`message` metadata와 `message_ciphertext(recipient_user_id=currentUserId, key_version=현재 active 버전)`를
별도의 DB Transaction에서 함께 저장한다. 외부 SMS 상대는 Look Talk 회원이 아니므로 그 상대용
`message_ciphertext`는 만들지 않는다.

평문 content는 DB, Application Log, 예외 메시지 어디에도 남기지 않는다.

Provider가 성공을 반환한 뒤 DB 저장 자체가 실패하는 경우(분산 트랜잭션 한계)는 이번 단계의 범위 밖이며,
outbox나 별도 재시도 인프라를 새로 도입하지 않는다.

Success Response는 HOSPITAL과 동일하게 공통 절 "### Success"(§57)를 따른다.

---

# WebSocket

# 58. WebSocket 실시간 메시지

### 구현 목적

REST API를 메시지 생성의 Source of Truth로 사용하고 WebSocket은 저장 완료된 메시지를 실시간으로 전달하는 용도로 사용한다.

```text
POST /messages
→ DB 저장
→ Transaction commit
→ WebSocket Event 발행
```

### 연결 Endpoint

```text
/ws
```

Spring STOMP(over WebSocket)를 사용한다. SockJS fallback은 사용하지 않는다.

WebSocket 연결 단계에서 JWT를 검증한다. 브라우저 native WebSocket handshake는 임의 커스텀 header를 보내기
어려우므로, Access Token은 `/ws` HTTP handshake가 아니라 **STOMP CONNECT frame의 native header**로 전달한다.

```text
Authorization: Bearer {accessToken}
```

기존 REST와 동일하게 `JwtTokenProvider`로 검증하며, Refresh Token이나 URL query parameter(`/ws?token=...`)로는
전달하지 않는다(로그/브라우저 히스토리에 토큰이 남는 위험을 피한다).

`/ws` HTTP upgrade 자체는 CONNECT 인증을 수행할 수 있도록 열어두지만, 이것이 "WebSocket을 인증 없이 쓸 수
있다"는 뜻은 아니다. STOMP CONNECT에 유효한 JWT가 없으면 그 세션은 연결을 완료하지 못한다(CONNECTED 프레임을
받지 못한다). REST API의 bearerAuth 정책은 이 경계와 무관하게 그대로 유지한다.

### 구독

사용자별 목적지를 사용한다.

```text
/user/queue/chat
```

room 전체 Topic으로 동일 ciphertext를 broadcast하지 않는다. 사용자마다 암호문이 다르므로 현재 사용자에게 해당하는 ciphertext만 전달한다.

인증된 client가 구독할 수 있는 destination은 `/user/queue/chat` 하나뿐이다. `/topic/**`, 다른 사용자의
`/user/{other}/queue/**` 등 그 외 destination 구독은 허용하지 않는다.

이 단계에서 client의 STOMP SEND frame은 사용하지 않는다. 메시지 생성은 항상 REST `CHAT-006`
(`POST /api/chat-rooms/{roomId}/messages`)만 Source of Truth이며, WebSocket으로 메시지를 만드는 경로는
제공하지 않는다.

### Event

```json
{
  "type": "MESSAGE_CREATED",
  "roomId": 10,
  "data": {
    "messageId": 320,
    "senderParticipantId": 5,
    "senderUserId": "U100",
    "senderDisplayName": "김철수",
    "encryptedPayload": {
      "algorithm": "LIBSODIUM_SEALED_BOX",
      "keyVersion": 1,
      "ciphertext": "BASE64_FOR_CURRENT_USER"
    },
    "createdAt": "..."
  }
}
```

Frontend는 수신한 ciphertext를 자신의 개인키로 복호화한다.

HOSPITAL room은 활성 내부 participant 각각에게 그 사용자용 `encryptedPayload`만 담은 별도 Event를 보낸다
(sender 본인도 포함해 자기 ciphertext Event를 받는다). SMS room은 현재 PATIENT에게만 Event를 보내며, 외부
SMS 상대는 Look Talk 사용자가 아니므로 WebSocket 수신자가 아니다.

### 전달 실패·오프라인 정책

DB commit 성공이 곧 `CHAT-006` 메시지 생성 성공이다. WebSocket publish는 이후 이어지는 실시간 전달
최적화이며, publish 실패나 수신자의 오프라인 상태가 이미 commit된 메시지나 REST 응답을 무효화하지 않는다.
재연결한 client는 누락된 이력을 `CHAT-005`로 복구한다. 즉 WebSocket Event는 best-effort 실시간 전달이지
메시지의 영속적 저장·재전송 채널이 아니다.

### 적용 범위

이번 단계는 단일 서버 인스턴스의 Spring simple broker를 기준으로 한다. 여러 backend 인스턴스로 scale-out될
때의 instance 간 WebSocket 전달(예: Redis/RabbitMQ broker relay)은 이번 범위 밖이다.

---

# 59. 외부 SMS 수신

### Endpoint

```http
POST /api/integrations/sms/inbound
```

SMS Provider Webhook 전용 Endpoint로 사용한다.

Provider가 확정되면 Provider별 Request DTO와 Signature 검증 방식을 작성한다.

### 처리 방식

Webhook Signature를 검증한다.

발신 전화번호와 서비스 수신 번호를 이용해 대상 SMS room과 PATIENT를 찾는다.

수신한 평문 SMS를 현재 환자의 공개키로 sealed box 암호화한다.

`message` metadata를 저장한다.

환자용 ciphertext를 `message_ciphertext`에 저장한다.

평문은 DB와 Application Log에 저장하지 않는다.

Transaction commit 후 환자 WebSocket 목적지로 암호화 메시지 Event를 전송한다.

외부 SMS는 Provider와 Backend가 평문을 처리하므로 완전한 E2EE로 간주하지 않는다. DB에는 ciphertext만 저장한다.

---

# InputSession

# 60. InputSession 설계 보류

`InputSession`은 현재 API 계약을 확정하지 않는다.

기존 `input_session_log` 테이블은 삭제하지 않고 유지한다. 다만 입력 지표, 각 지표의 단위, 세션 생성 시점, 분석 화면에서 필요한 집계 방식이 확정되기 전까지 Controller, Service, DTO, Repository 구현을 진행하지 않는다.

현재 문서에서는 다음 Endpoint를 구현 계약으로 사용하지 않는다.

```text
POST /api/input-sessions
GET /api/input-sessions
GET /api/input-sessions/summary
```

프론트/AI 파트에서 실제 요구사항이 확정된 후 `input_session_log` 컬럼과 API Request/Response를 함께 재정의한다.

Raw 카메라 프레임, 얼굴 영상 등 생체 원시 데이터는 향후 InputSession 설계에서도 서버 저장 대상에 포함하지 않는 것을 기본 원칙으로 한다.


# 61. 백엔드 패키지 구조

```text
com.looktalk.backend
│
├── global
│   ├── config
│   ├── response
│   │   └── ApiResponse.java
│   ├── exception
│   │   ├── ErrorCode.java
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   ├── security
│   │   ├── SecurityConfig.java
│   │   ├── JwtTokenProvider.java
│   │   └── JwtAuthenticationFilter.java
│   └── swagger
│
├── auth
│   ├── controller
│   ├── service
│   ├── dto
│   └── refreshtoken
│       ├── entity
│       └── repository
│
├── user
│   ├── controller
│   ├── service
│   ├── dto
│   ├── entity
│   └── repository
│
├── e2ee
│   ├── controller
│   ├── service
│   ├── dto
│   ├── entity
│   └── repository
│
├── hospital
├── staff
├── memo
├── phrase
├── friendship
└── chat
```

E2EE 도메인에서 Backend가 수행하는 기능은 공개키 등록·조회와 ciphertext 저장 형식 관리로 제한한다.

개인키는 Backend에 두지 않는다.

SMS inbound/outbound에서는 Backend가 사용자의 공개키를 이용해 저장용 ciphertext를 생성한다.

---

# 62. User Entity

`user_name`은 nullable로 매핑한다.

```java
@Column(name = "user_name", length = 50)
private String userName;
```

회원가입 생성 로직에서 이름을 parameter로 요구하지 않는다.

`user_pwd`는 평문 Setter를 그대로 저장하지 않고 Auth Service에서 PasswordEncoder를 거친 값만 저장한다.

---

# 63. 사용자 표시명 처리

여러 Service에서 표시 이름 로직을 반복하지 않는다.

다음 Resolver 또는 Mapper를 공통으로 사용한다.

```java
public String resolveDisplayName(User user) {
    if (user.getUserName() == null || user.getUserName().isBlank()) {
        return user.getLoginId();
    }
    return user.getUserName();
}
```

User Response, Staff Response, 담당 환자 Response, 내부 채팅 연락처, 채팅 상대, 메시지 작성자 Response에 동일하게 적용한다.

---

# 64. E2EE DTO 처리

공통 암호화 DTO를 사용한다.

```java
public record EncryptedPayload(
        String algorithm,
        Integer keyVersion,
        String ciphertext
) {
}
```

Backend는 `algorithm=LIBSODIUM_SEALED_BOX`인지 확인한다.

ciphertext를 Base64 decode 가능한지 확인한다.

Backend Service에서 복호화 메서드를 제공하지 않는다.

병원 메시지 Request에서는 participant별 ciphertext DTO를 사용한다.

```java
public record RecipientCiphertext(
        String recipientUserId,
        Integer keyVersion,
        String algorithm,
        String ciphertext
) {
}
```

---

# 65. Exception 구현

Service에서 비즈니스 조건 위반 시 `BusinessException`을 발생시킨다.

```java
throw new BusinessException(ErrorCode.MEMO_NOT_FOUND);
```

Controller에서 개별 실패 Response를 조립하지 않는다.

`GlobalExceptionHandler`에서 ErrorCode의 HTTP Status와 공통 `ApiResponse`를 생성한다.

Spring Security의 401, 403도 가능한 한 동일한 JSON Error Response 형식으로 반환한다.

---

# 66. Transaction 적용 대상

| 기능 | Transaction 범위 |
|---|---|
| 환자 회원가입 | users + user_setting |
| 의료진 회원가입 | users + staff |
| Refresh Token 재발급 | 기존 Refresh Token revoke + 신규 Refresh Token 저장 |
| 비밀번호 재설정 | password 변경 + reset token 초기화 + Refresh Token 전체 revoke |
| 회원탈퇴 | token/관계/사용자 데이터 정리 + users 삭제 |
| 담당 환자 추가 | 중복 검사 + assignment 생성 |
| 담당 환자 삭제 | assignment 조회 + 삭제 |
| Phrase 등록 | count/lock + oldest delete + 신규 insert |
| 병원 채팅방 생성 | chat_room + participant 2건 |
| SMS 채팅방 생성 | chat_room + participant 2건 |
| 병원 메시지 생성 | message + participant별 message_ciphertext |
| SMS 메시지 저장 | message + patient ciphertext |
| 전화번호 인증 완료 | verification + users 변경 |

메일/SMS Provider 호출은 장시간 DB Transaction을 유지하지 않도록 외부 호출과 DB Transaction 경계를 분리한다.

SMS 전송은 Provider가 요청을 수락한 이후 메시지 metadata와 ciphertext를 저장한다.

---

# 67. 채팅방 중복 생성 방지

병원 채팅방과 SMS 채팅방은 `chat_room.conversation_key`를 이용해 동일 상대에 대한 중복 생성을 방지한다.

병원 1:1 room은 두 userId를 정렬한 뒤 다음 값을 사용한다.

```text
HOSPITAL:{smallUserId}:{largeUserId}
```

SMS room은 다음 값을 사용한다.

```text
SMS:{currentUserId}:{normalizedFriendPhone}
```

`conversation_key`에는 NULL이 아닌 값에 대해 UNIQUE 인덱스를 적용한다.

Service는 Transaction 안에서 기존 room을 먼저 조회하고 없으면 생성한다. 동시에 동일한 생성 요청이 들어와도 DB UNIQUE 인덱스가 최종 중복 방어 수단으로 동작한다.

---

# 68. 로그 보안 정책

다음 값은 Application Log에 기록하지 않는다.

```text
password
Authorization Header
Access Token
Refresh Token
Reset Token
verification code
memo plaintext
phrase plaintext
hospital chat plaintext
SMS plaintext
E2EE private key
```

Request/Response 전체 Body를 운영 로그에 그대로 출력하지 않는다.

Spring Security debug log는 운영 환경에서 비활성화한다.

DB 연결 비밀번호, JWT Secret, SMS Provider Secret, 메일 Provider Secret은 Git에 저장하지 않는다.

환경변수 또는 Secret 관리 도구를 사용한다.

---

# 69. Swagger 구현 기준

각 API에 `@Operation`으로 기능과 서버 처리 기준을 작성한다.

예:

```java
@Operation(
    summary = "환자 회원가입",
    description = "아이디, 이메일, 비밀번호를 이용해 환자 계정을 생성한다. 이메일 인증이 완료된 사용자만 가입할 수 있다. 이름은 회원가입 이후 마이페이지에서 선택적으로 설정한다."
)
```

Request DTO에는 `@Schema`를 사용한다.

E2EE ciphertext 필드에는 실제 평문 예제를 넣지 않고 Base64 placeholder를 사용한다.

---

# 70. 구현 순서

백엔드 작업은 다음 순서로 진행한다.

```text
1. DB Migration
   - refresh_token
   - user_e2ee_key
   - message_ciphertext
   - memo/phrase key_version
   - message.content 제거
   - 필요한 index/unique constraint

2. Global
   - ApiResponse
   - ErrorCode
   - BusinessException
   - GlobalExceptionHandler
   - Validation 처리

3. Security / Auth
   - PasswordEncoder
   - JWT Access Token
   - Refresh Token
   - Refresh Token Rotation
   - Logout
   - 비밀번호 재설정
   - SecurityConfig

4. User / Staff
   - 마이페이지
   - 이름 설정
   - displayName
   - 전화번호 인증
   - 회원탈퇴
   - 담당 환자 관리

5. E2EE Key
   - 공개키 등록
   - 내 키 상태 조회
   - 상대 공개키 조회

6. Memo / Phrase
   - ciphertext 저장
   - 사용자 소유권 검증
   - Phrase 최대 3개 FIFO 정책

7. Friend
   - SMS 인증 확인
   - 친구 등록/수정/삭제

8. Chat REST
   - 내부 회원 연락처 조회
   - 채팅방 목록
   - 병원 채팅방 find-or-create
   - SMS 채팅방 find-or-create
   - ciphertext 메시지 저장/조회

9. WebSocket
   - JWT 인증
   - user destination
   - ciphertext event 전송

10. SMS Provider
    - 인증번호 발송
    - SMS outbound
    - inbound webhook
    - public key 기반 저장용 ciphertext 생성
```

---

# 71. 구현 전 확정이 필요한 항목

| 항목 | 현재 명세 기준 |
|---|---|
| 로그인 | 모든 역할이 `loginId + password`를 사용한다 |
| 이름 | 회원가입에서 받지 않고 마이페이지에서 선택 입력한다 |
| 이름 미설정 표시 | 내부 회원은 `login_id`를 표시한다 |
| 이메일 | 원문 저장, 이메일 인증·비밀번호 재설정에 사용한다 |
| 전화번호 | 원문 저장, SMS 기능에 사용한다 |
| Refresh Token | 도입하고 HttpOnly Cookie + DB hash 방식으로 관리한다 |
| 환자 병원 소속 | 필수로 두지 않는다 |
| 내부 병원 채팅 대상 | 모든 PATIENT와 STAFF를 허용한다 |
| 담당 환자 관계 | 환자 관리 기능에만 사용하고 채팅 권한에는 사용하지 않는다 |
| 의료진 탈퇴 | 담당 환자가 있어도 허용하고 assignment를 정리한다 |
| 자주 쓰는 문장 | 최대 3개, 4번째 등록 시 가장 오래된 문장을 자동 삭제한다 |
| 자주 쓰는 문장 등록 위치 | 마이페이지에서 등록한다 |
| Phrase 삭제 API | 사용자의 수동 삭제를 위해 유지한다 |
| 친구 등록 | 본인 전화번호 인증 완료 후 허용한다 |
| 친구 탭 표시 | `friendship` 조회 결과를 그대로 사용한다 |
| SMS 채팅방 생성 | 친구를 실제로 클릭할 때 find-or-create한다 |
| 병원 채팅방 생성 | 내부 회원을 실제로 클릭할 때 find-or-create한다 |
| Memo | E2EE ciphertext만 서버에 저장한다 |
| Phrase | E2EE ciphertext만 서버에 저장한다 |
| 병원 채팅 | 사용자별 E2EE ciphertext만 서버에 저장한다 |
| SMS 채팅 | Provider 처리 시 평문을 일시 사용하고 DB에는 ciphertext만 저장한다 |
| E2EE private key | Backend에 저장하지 않는다 |
| E2EE 기능 선행조건 | Memo/Phrase 생성·수정, 병원 메시지 전송, SMS 채팅방 생성·전송 전에 필요한 공개키 등록 여부를 검증한다 |
| E2EE 다중 디바이스/복구 | v1 범위에서 제외한다 |
| SMS Provider | 별도 확정이 필요하다 |
| InputSession | 전체 설계 보류. 지표·단위·저장 시점·API 계약 확정 후 별도 작성한다 |
| Calibration | 구현하지 않는다 |
| Recommendation | 구현하지 않는다 |
| Emergency | 구현하지 않는다 |

---

# 72. E2EE 운영 제약

v1에서는 개인키를 Backend에 저장하지 않는다.

사용자의 브라우저 저장소가 삭제되거나 다른 기기에서 접속하면 기존 ciphertext를 복호화할 수 없다.

다중 디바이스 및 키 복구가 필요해지는 경우 다음 단계에서 별도 키 복구 설계를 추가한다.

```text
Encrypted Private Key Backup
Recovery Key
Device Registration
Key Rotation
```

이 기능을 추가하기 전까지 E2EE Key Pair를 임의로 재생성하지 않는다. 기존 Key Version으로 암호화된 Memo, Phrase, 병원 채팅 기록을 계속 복호화할 수 있어야 한다.
