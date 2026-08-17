# LookTalk Backend 2차 작업 정리안

> 목적: 현재 V1~V7 및 기존 41개 REST API + WebSocket 구현을 기준선으로 유지하면서 Emergency, Calibration, InputSession, Recommendation, 의료진별 환자 별칭, 이메일 실발송을 확장한다. Claude는 반드시 실제 Repository를 먼저 확인하고 단계별로 구현·검증한다.

---

## 1. 기본 원칙

- V1~V7 migration은 절대 수정하지 않는다. DB 변경은 V8 이후 신규 migration으로만 추가한다.
- 실제 V1~V7 SQL, Entity, Repository, Service가 예전 문서와 다르면 실제 코드를 기준으로 한다.
- 기존 `ApiResponse<T>`, `ErrorCode`, Security, OpenAPI, CORS 구조를 유지한다.
- PATIENT/STAFF 권한은 Security URL rule과 Service에서 모두 재검증한다.
- 기존 API field를 rename/remove하지 않는다. 신규 field는 backward-compatible하게 추가한다.
- mock 추천, fake SMS 성공, raw OTP 로그, raw biometric frame 저장을 금지한다.
- 기존 일반 표시명 규칙 `userName -> loginId`는 유지하고 UUID `userId`를 표시명으로 사용하지 않는다.

## 2. 2차 작업 최종 범위

### 구현

| 구분 | 작업 |
|---|---|
| V8 | `emergency_call` 신설 |
| V8 | `chat_room.room_type` CHECK 보강 |
| V8 | `input_session_log.user_id` index 추가 |
| Emergency | 호출 POST / 담당 호출 GET / 읽음 PATCH |
| WebSocket | `/user/queue/emergency` 추가 |
| Email | 실제 SMTP `EmailSender` 구현체 추가 |
| V9 | `calibration` 테이블 신설 |
| V9 | `input_session_log` 분석용 컬럼/제약 보강 |
| V9 | `user_setting.recommended_input_method`, `recommended_at`, `retest_opt_out` 추가 |
| V9 | `staff_patient_assignment.patient_alias` 추가 |
| Calibration | 저장 + 활성 Calibration 조회 API |
| InputSession | 검사 결과 저장 + 목록/최근 결과 조회 API |
| Recommendation | 실제 확정 규칙 기반 추천 + 상태 조회/재검사 거부 저장 |
| Staff | 담당 환자 별칭 수정 API |
| Chat 표시명 | STAFF가 자기 담당 환자를 볼 때 자기 alias 우선 적용 |

### 이번 단계에서 추가하지 않음

- `users.hospital_display_name`: 환자가 일반 이름과 병원용 이름을 별도로 설정하는 UI가 확정될 때만 추후 migration에서 추가한다. 현재 요구는 `user_name` + 의료진별 `patient_alias`로 해결한다.
- `users.privacy_agreed_at`: 실제 회원가입 동의 Request/저장 흐름이 확정되기 전에는 NULL 컬럼만 미리 추가하지 않는다.
- SMS Provider + §59 inbound: Provider 선정/발신번호 승인 이후 별도 작업. 현재 503 동작을 fake success로 바꾸지 않는다.
- 로그인 상태 비밀번호 변경 API: 공모전 이후.

---

## 3. 구현 순서

1. Repository 실제 상태 재확인
2. V8 migration
3. Emergency REST
4. Emergency WebSocket
5. 이메일 실발송
6. 입력 지표 계약 확인
7. V9 migration
8. Calibration API
9. InputSession API
10. Recommendation
11. `patient_alias` API + STAFF/Chat 표시명 반영
12. 전체 regression + Swagger + docs

각 단계 완료 후 compile/test/DB 검증을 한 뒤 다음 단계로 진행한다.

---

## 4. Phase 0 — 구현 전 반드시 확인

### Migration

`src/main/resources/db/migration/V1~V7`을 직접 읽고 다음을 확인한다.

- `users.user_id` 실제 type
- `staff.staff_id` 실제 type
- `staff_patient_assignment` 현재 column/FK/UNIQUE
- `input_session_log` 현재 column, PK type, `calibration_id` 존재 여부
- `input_session_log.input_method` 현재 constraint
- `user_setting` 현재 column
- `chat_room.room_type` 현재 CHECK 존재 여부

### Java

최소 다음을 읽는다.

- `ErrorCode`
- `SecurityConfig`
- `DisplayNameResolver`
- `UserSetting` Entity/DTO/Service
- `StaffPatientAssignment` Entity/Repository
- `StaffService`, `StaffController`
- `ChatContactService`, `ChatRoomService`, `ChatMessageService`
- `ChatRealtimePublisher`
- `WebSocketAuthChannelInterceptor`, `WebSocketConfig`
- `EmailSender`, `LoggingEmailSender`

작업 전 실행:

```bash
git status --short
./gradlew clean test
```

기존 테스트가 깨져 있으면 신규 기능부터 구현하지 않는다.

---

# 5. V8 — Emergency + 무결성 보강

예상 migration:

```text
V8__add_emergency_and_integrity_constraints.sql
```

## 5.1 `emergency_call`

호출 1회당 1행 저장한다. 반복 횟수 컬럼은 두지 않고 조회 시 집계한다.

| column | 규칙 |
|---|---|
| `emergency_call_id` | PK, Long 계열 서버 채번 |
| `patient_user_id` | NOT NULL FK -> users, ON DELETE CASCADE |
| `called_at` | NOT NULL TIMESTAMPTZ |
| `read_at` | nullable TIMESTAMPTZ |
| `read_by_staff_id` | nullable FK -> staff, ON DELETE SET NULL |

`read_by_staff_id`를 SET NULL로 두어 호출을 읽은 STAFF가 탈퇴해도 기존 USER-005 탈퇴 정책을 막지 않는다. `read_at`은 유지한다.

index:

```text
ix_emergency_call_patient_user_id(patient_user_id)
```

단순 patient index이므로 이름에 `unread`를 붙이지 않는다.

## 5.2 `chat_room.room_type` CHECK

허용값을 Java enum과 동일하게 고정한다.

```text
HOSPITAL
SMS
```

기존 constraint가 이미 있으면 중복 생성하지 않는다.

## 5.3 `input_session_log.user_id` index

```text
ix_input_session_log_user_id(user_id)
```

실제 V7 schema를 확인하고 추가한다.

---

# 6. Emergency REST

패키지는 기존 convention에 맞춰 `emergency/controller,dto,entity,repository,service,websocket` 구조로 추가한다.

## EMERGENCY-001 호출 발생

```http
POST /api/emergency-calls
```

- PATIENT 전용
- body 없음
- JWT userId 사용
- 호출 행 저장 후 commit
- commit 이후 담당 STAFF 각각에게 WebSocket 발행
- 담당 STAFF가 0명이어도 호출 자체는 성공 저장

Response data:

```json
{
  "emergencyCallId": 10,
  "calledAt": "..."
}
```

## EMERGENCY-002 담당 환자 호출 조회

```http
GET /api/emergency-calls?page=0&size=20&unreadOnly=false
```

- STAFF 전용
- 현재 STAFF와 `staff_patient_assignment` 관계가 있는 환자 호출만 반환
- 다른 환자 호출 노출 금지
- `called_at DESC`, 동일 시각이면 `emergency_call_id DESC`

item 권장:

```json
{
  "emergencyCallId": 10,
  "patientUserId": "...",
  "patientDisplayName": "김철수",
  "calledAt": "...",
  "readAt": null,
  "readByStaffId": null,
  "unread": true
}
```

V9 alias 구현 후 `patientDisplayName`은 현재 STAFF의 `patientAlias -> userName -> loginId` 규칙을 사용한다.

## EMERGENCY-003 읽음 처리

```http
PATCH /api/emergency-calls/{emergencyCallId}/read
```

- STAFF 전용
- 현재 STAFF가 해당 환자의 담당자인 경우만 허용
- 미담당/없는 호출은 존재 노출을 피하기 위해 동일하게 `EMERGENCY_CALL_NOT_FOUND` 404
- 최초 reader만 저장하고 이후 호출은 기존 read 정보 반환
- 동시 읽기에서 마지막 요청이 최초 reader를 덮어쓰면 안 됨
- pessimistic lock 또는 `UPDATE ... WHERE read_at IS NULL` 방식 사용

---

# 7. Emergency WebSocket

기존:

```text
/user/queue/chat
```

추가:

```text
/user/queue/emergency
```

`WebSocketAuthChannelInterceptor` SUBSCRIBE allowlist에 정확히 추가한다. 기존 STOMP CONNECT JWT 인증, client SEND 금지, `/user/queue/chat` 동작은 그대로 유지한다.

Event:

```json
{
  "type": "EMERGENCY_CALL_CREATED",
  "data": {
    "emergencyCallId": 10,
    "patientUserId": "...",
    "patientDisplayName": "김철수",
    "calledAt": "..."
  }
}
```

발송 대상은 현재 담당 STAFF 각각이다.

Transaction 원칙:

```text
REST -> DB COMMIT -> WebSocket publish
```

publish 실패가 이미 commit된 호출을 rollback시키지 않는다. REST 조회가 Source of Truth다.

---

# 8. 이메일 실발송

기존 `EmailSender` interface를 유지한다.

권장:

```text
EmailSender
├─ LoggingEmailSender   # local/test
└─ SmtpEmailSender      # 실제 발송
```

- Provider 종속 코드를 `AuthService`/`VerificationCodeService`에 넣지 않는다.
- SMTP credentials는 환경변수로만 주입한다.
- 인증번호 원문, 이메일+인증번호 조합을 로그에 남기지 않는다.
- AUTH-001/002 API contract는 변경하지 않는다.
- local/test와 smtp bean이 동시에 활성화되어 bean 충돌이 나지 않도록 profile/property로 명확히 분리한다.

환경변수 예시는 현재 config convention에 맞춰 결정한다.

```text
MAIL_HOST
MAIL_PORT
MAIL_USERNAME
MAIL_PASSWORD
MAIL_FROM
MAIL_PROVIDER
```

---

# 9. 입력 지표 계약 — V9 코드 전 필수

지표:

```text
typoRate
inputSpeed
recognitionAccuracy
inputStability
```

팀이 확정해야 하는 것:

- 산식
- 단위
- 측정 시작/종료 시점
- 값 범위
- null 정책
- 추천 계산 규칙

Backend가 임의로 지표 산식/추천 threshold를 만들면 안 된다.

기본 validation 후보:

```text
typoRate             0.0 ~ 1.0
recognitionAccuracy  0.0 ~ 1.0
inputStability       0.0 ~ 1.0
inputSpeed           0 이상
```

`inputSpeed` 단위는 정의서 확정 후 문서/API에 명시한다.

---

# 10. V9 — Calibration / InputSession / Recommendation / patient_alias

예상 migration:

```text
V9__add_calibration_analysis_and_patient_alias.sql
```

## 10.1 `calibration` 신설

웹에서 계산한 최종 Calibration parameter만 저장한다.

저장 금지:

- 웹캠 원본 영상/프레임
- 눈 이미지
- 프레임별 raw landmark
- 프레임별 raw gaze sequence

논리 schema:

| column | 규칙 |
|---|---|
| `calibration_id` | PK, Backend UUID 문자열 |
| `user_id` | NOT NULL FK -> users |
| `input_method` | NOT NULL, InputMethod enum과 동일 |
| `algorithm_version` | NOT NULL |
| `calibration_data` | JSONB NOT NULL |
| `quality_score` | nullable, 있으면 0~1 |
| `viewport_width` | NOT NULL |
| `viewport_height` | NOT NULL |
| `device_pixel_ratio` | nullable |
| `created_at` | NOT NULL |
| `revoked_at` | nullable |

`user_id` type은 실제 users PK type과 맞춘다.

사용자 + inputMethod별 active calibration은 최대 1개(`revoked_at IS NULL`). 새 calibration 저장 시 기존 active를 revoke한다. 동시 등록에도 active 2개가 생기지 않도록 DB unique/lock으로 방어한다.

`calibration_data`는 알고리즘 변경 가능성을 위해 JSONB로 둔다.

권장 envelope:

```json
{
  "schemaVersion": 1,
  "mappingType": "RIDGE",
  "parameters": {}
}
```

Claude가 현재 gaze 알고리즘 내부 parameter 이름을 임의로 만들어 DB column으로 고정하지 않는다.

## 10.2 `input_session_log` 보강

실제 V7 schema에 없는 것만 추가한다.

`input_method` CHECK:

```text
EYE_TRACKING
BLINK
MOUTH
```

`session_type`:

```text
CALIBRATION_TEST
NORMAL_USE
```

현재 공모전 전 실제 생성은 우선 `CALIBRATION_TEST`를 사용한다. 기존 row가 있으면 안전하게 backfill 후 NOT NULL 전환한다.

`calibration_id`가 현재 없다면 nullable FK로 추가한다.

정책:

```text
EYE_TRACKING + CALIBRATION_TEST -> calibrationId 필수
BLINK/MOUTH                     -> nullable
NORMAL_USE                      -> 현재 nullable 허용
```

Calibration은 revoke이지 delete가 아니므로 과거 log가 참조한 calibration row는 유지한다.

`log_id`는 현재 PK type을 유지하고 Frontend가 보내지 않는다.

```java
UUID.randomUUID().toString()
```

으로 Backend가 생성한다.

## 10.3 `user_setting`

추가:

```text
recommended_input_method NULL
recommended_at TIMESTAMPTZ NULL
retest_opt_out BOOLEAN NOT NULL DEFAULT FALSE
```

`recommended_input_method` CHECK는 InputMethod enum과 동일.

```text
current_input_method      = 실제 현재 사용 방식
recommended_input_method  = 최근 검사 기반 추천 방식
```

추천이 생겨도 `current_input_method`를 자동 변경하지 않는다.

## 10.4 `staff_patient_assignment.patient_alias`

```text
patient_alias VARCHAR(50) NULL
```

특정 STAFF가 특정 PATIENT를 자기 화면에서 보기 위한 개인 별칭이다.

```text
환자 user_name = 김철수
STAFF A alias = 101호_김철수
STAFF B alias = NULL

환자 본인 -> 김철수
STAFF A   -> 101호_김철수
STAFF B   -> 김철수
```

한 STAFF의 alias를 다른 STAFF/PATIENT에게 노출하지 않는다.

---

# 11. Calibration API

PATIENT 전용.

## CAL-001 저장

```http
POST /api/calibrations
```

Request 예시:

```json
{
  "inputMethod": "EYE_TRACKING",
  "algorithmVersion": "gaze-mapping-v1",
  "calibrationData": {
    "schemaVersion": 1,
    "mappingType": "RIDGE",
    "parameters": {}
  },
  "qualityScore": 0.91,
  "viewportWidth": 1920,
  "viewportHeight": 1080,
  "devicePixelRatio": 1.0
}
```

- calibrationId는 Backend UUID
- 새 row 저장 시 기존 active revoke
- calibrationData를 Backend가 해석/복호화/재계산하지 않음

## CAL-002 active 조회

```http
GET /api/calibrations/active?inputMethod=EYE_TRACKING
```

- 본인 active만 조회
- 프론트 재사용을 위해 `calibrationData` 포함
- 없으면 `CALIBRATION_NOT_FOUND` 404

---

# 12. InputSession API

PATIENT 전용.

## INPUT-001 저장

```http
POST /api/input-sessions
```

```json
{
  "sessionType": "CALIBRATION_TEST",
  "inputMethod": "EYE_TRACKING",
  "calibrationId": "...",
  "typoRate": 0.12,
  "inputSpeed": 63.2,
  "recognitionAccuracy": 0.91,
  "inputStability": 0.87
}
```

처리:

1. PATIENT 재검증
2. validation
3. calibrationId가 있으면 현재 사용자 소유인지 확인
4. EYE_TRACKING + CALIBRATION_TEST인데 calibrationId가 없으면 거부
5. Backend가 logId UUID 생성
6. JWT userId로 저장
7. 실제 추천 규칙이 준비된 경우 RecommendationService 재계산

다른 사용자의 calibrationId는 `CALIBRATION_NOT_FOUND`로 처리한다.

## INPUT-002 목록/필터

```http
GET /api/input-sessions?inputMethod=EYE_TRACKING&sessionType=CALIBRATION_TEST&page=0&size=20
```

- 현재 로그인 PATIENT log만
- filter optional
- `session_at DESC` + 안정적인 PK 정렬
- 데이터 없음은 정상 empty list

## INPUT-003 최근 결과

```http
GET /api/input-sessions/latest?inputMethod=EYE_TRACKING&sessionType=CALIBRATION_TEST
```

없으면 `INPUT_SESSION_NOT_FOUND` 404. 프론트 분석 empty state에서 처리한다.

---

# 13. Recommendation

추천 threshold/가중치/최소 데이터 수/동점 처리 규칙이 확정되기 전에는 임의 추천을 구현하지 않는다.

권장 구조:

```text
RecommendationService
        ↓
InputMethodRecommendationPolicy
        ↓
팀이 확정한 실제 규칙 구현체
```

추천 결과가 나오면:

```text
user_setting.recommended_input_method
user_setting.recommended_at
```

만 갱신한다. 현재 입력 방식은 자동 변경하지 않는다.

## REC-001 추천 상태 조회

```http
GET /api/users/me/input-recommendation
```

Response 예시:

```json
{
  "currentInputMethod": "EYE_TRACKING",
  "recommendedInputMethod": "BLINK",
  "recommendedAt": "...",
  "retestOptOut": false,
  "hasRecommendation": true,
  "changed": true
}
```

추천이 없으면 404보다 200 + `hasRecommendation=false`를 사용한다.

## REC-002 재검사 거부 설정

```http
PATCH /api/users/me/input-recommendation/preferences
```

```json
{
  "retestOptOut": true
}
```

true/false 모두 수정 가능하고 재로그인 후에도 유지한다.

---

# 14. 의료진별 환자 별칭 API

기존 전역 `DisplayNameResolver`는 그대로 둔다.

```text
일반 표시명 = userName -> loginId
```

STAFF가 자기 담당 PATIENT를 보는 문맥에서만:

```text
patientAlias -> userName -> loginId
```

별도 `StaffPatientDisplayNameResolver` 같은 helper를 둔다. helper 내부에서 목록마다 반복 DB query를 만들지 말고 Service가 assignment를 조회해 alias를 전달하는 방향을 우선한다.

## STAFF-005 alias 수정

```http
PATCH /api/staff/me/patients/{patientUserId}/alias
```

Request:

```json
{
  "alias": "101호_김철수"
}
```

규칙:

- STAFF 전용
- 최대 50자
- trim
- null/blank -> NULL 저장(별칭 제거)
- 현재 STAFF 자신의 assignment만 수정
- 미담당이면 기존 `STAFF_PATIENT_NOT_ASSIGNED` 404 재사용

Response는 수정된 담당 환자 정보를 반환한다. 기존 `StaffPatientResponse` field를 제거/rename하지 말고 `patientAlias` 및 alias-aware `displayName`을 추가/반영한다.

기존 `GET /api/staff/me/patients`도:

```text
displayName = patientAlias -> userName -> loginId
```

로 반환한다.

---

# 15. patient_alias의 Chat 적용

요구: STAFF A가 환자 김철수를 `101호_김철수`로 설정하면 **STAFF A가 보는 병원 채팅에서만** 해당 이름을 사용한다.

## CHAT-002 room list

현재 사용자=STAFF, 상대=담당 PATIENT이면:

```text
target.displayName = patientAlias -> userName -> loginId
```

PATIENT가 같은 room을 볼 때 STAFF alias를 반환하면 안 된다.

## CHAT-005 message history

현재 사용자=STAFF이고 sender가 자기 담당 PATIENT이면 alias-aware `senderDisplayName`을 반환한다.

- PATIENT에게 STAFF alias 노출 금지
- withdrawn sender의 `탈퇴한 사용자` 유지
- 외부 SMS snapshot 이름 유지

## WebSocket `MESSAGE_CREATED`

PATIENT -> STAFF 이벤트의 `senderDisplayName`은 **recipient STAFF 자신의 alias**를 반영한다. 다른 recipient에게 같은 alias를 재사용하지 않는다. 기존 per-recipient ciphertext isolation 원칙을 유지한다.

## CHAT-001 contact list

이번 alias 작업으로 Chat 대상 eligibility를 바꾸지 않는다. assignment가 있는 PATIENT의 `displayName`만 alias-aware하게 할 수 있다.

`patient_alias` keyword 검색 및 STAFF 채팅 대상 제한은 Chat 정책 확정 후 함께 수정한다.

---

# 16. 아직 확정되지 않은 Chat 정책 — 임의 구현 금지

다음은 팀 결정 전 현재 동작을 유지한다.

> STAFF의 병원 채팅 대상을 담당 PATIENT만으로 제한할 것인가?

확정 시 CHAT-001만 바꾸면 안 된다. 최소 함께 검토:

```text
CHAT-001 연락처
CHAT-003 room 생성
CHAT-006 메시지 전송
CHAT-002 배정 해제 후 room 노출
CHAT-005 배정 해제 후 과거 메시지 조회
PATIENT가 미담당 STAFF에게 먼저 room을 만들 수 있는지
```

한쪽 경로만 제한하면 API 직접 호출로 우회 가능하다.

---

# 17. 비밀번호 정책

이번 2차 Backend 작업에서 기존 `8~64자` validation을 변경하지 않는다.

와이어프레임의 `4~6자리` 문구만으로 Backend를 낮추지 않는다. 정말 4~6자리 간편 인증이 필요하면 별도 PIN 기능으로 설계한다.

---

# 18. 신규 ErrorCode 후보

실제 `ErrorCode`를 먼저 읽고 중복 의미가 없는 경우에만 추가한다.

```text
EMERGENCY_CALL_NOT_FOUND       404
CALIBRATION_NOT_FOUND          404
CALIBRATION_REQUIRED           409
CALIBRATION_INVALID_PAYLOAD    400
INPUT_SESSION_NOT_FOUND        404
```

Alias는 기존 `STAFF_PATIENT_NOT_ASSIGNED` 404를 재사용한다. Bean Validation은 기존 `COMMON_VALIDATION_ERROR` 흐름을 사용한다.

---

# 19. Security 권한

논리 권한:

```text
POST  /api/emergency-calls/**             -> PATIENT
GET   /api/emergency-calls/**             -> STAFF
PATCH /api/emergency-calls/**             -> STAFF
/api/calibrations/**                       -> PATIENT
/api/input-sessions/**                     -> PATIENT
/api/users/me/input-recommendation/**      -> PATIENT
/api/staff/**                              -> 기존 STAFF rule
```

실제 matcher는 현재 `SecurityConfig` 구조를 읽고 적용한다. Service role check도 유지한다.

---

# 20. 필수 테스트

## Migration

- V1~V7 diff 없음
- pristine DB에서 V1~V8 성공
- pristine DB에서 V1~V9 성공
- Hibernate validate 성공

## Emergency

- PATIENT 호출 성공 / STAFF 호출 403 / 미인증 401
- 담당 STAFF만 조회
- 미담당 STAFF 호출 노출 없음
- unread filter
- 최초 read만 저장
- 동시 read에서도 최초 reader 보존
- 읽은 STAFF 탈퇴 후 call 보존
- 담당 STAFF 0명 호출 저장 성공

## Emergency WebSocket

- JWT 없는 CONNECT 실패
- `/user/queue/emergency` 구독 성공
- 임의 destination 구독 실패
- 담당 STAFF만 이벤트 수신
- publish 실패가 REST/DB commit을 무효화하지 않음
- `/user/queue/chat` regression 없음

## Calibration

- PATIENT 전용
- server UUID
- 신규 저장 시 기존 active revoke
- 동일 사용자+inputMethod active 최대 1
- 동시 저장 안전
- cross-user 접근 차단
- JSON round trip

## InputSession

- server logId
- cross-user calibration 차단
- EYE_TRACKING test calibration 필수
- 수치 validation
- 본인 log만 조회
- filter/empty/latest 정상

## Recommendation

- mock/random 없음
- deterministic 실제 규칙
- currentInputMethod 자동 변경 안 함
- recommended 상태 저장
- retestOptOut 영속

## patient_alias

- 자기 담당 환자만 수정
- 50자 validation
- null/blank 삭제
- 다른 STAFF 수정 불가
- 환자 `user_name` 불변
- STAFF A alias가 STAFF B/PATIENT에게 노출되지 않음
- STAFF-002, CHAT-002, CHAT-005, WebSocket recipient별 표시 정상

---

# 21. 전체 회귀 검증

마지막에 반드시:

```bash
./gradlew clean test
```

및 실제 boot 검증을 수행한다.

기존 Auth/User/Staff/E2EE/Memo/Phrase/Friend/CHAT-001~006/WebSocket/USER-005가 깨지지 않아야 한다.

특히 `patient_alias` 때문에 전역 `DisplayNameResolver`를 변경해서 기존 사용자 표시가 바뀌면 안 된다.

---

# 22. Swagger / 문서

실제 구현 후 갱신:

```text
docs/LookTalk_Backend_API_Spec_v2.1.md
docs/LookTalk_Backend_Implementation.md
docs/database.md
```

Swagger에서 신규 API의 bearerAuth, request/response schema, enum, nullable, error schema를 확인한다. 기존 `CommonErrorResponseCustomizer`를 깨지 않는다.

---

# 23. Claude 실행 규칙

각 Step 시작 전:

1. 관련 기존 SQL/코드를 읽는다.
2. 이 문서와 실제 구조 충돌 여부를 먼저 보고한다.
3. 수정 대상 파일 목록을 제시한다.
4. 정책 결정이 필요한 부분은 임의 구현하지 않고 질문한다.

각 Step 완료 후:

1. compile
2. test
3. migration 검증
4. DB 상태 검증
5. 실제 HTTP/Swagger 검증
6. regression 검증
7. 임시 test endpoint/file 삭제 확인
8. `git status --short` 확인

금지:

- V1~V7 수정
- 기존 API field rename/remove
- fake provider 성공
- mock 추천 저장
- raw OTP/비밀번호/토큰 로그
- raw biometric frame 저장
- `patient_alias`를 `users.user_name`에 덮어쓰기
- 한 STAFF의 alias를 다른 사용자에게 반환
- Chat 대상 정책 임의 변경

---

# 24. 권장 작업 단위

### Step 1
`V8 + Emergency Entity/Repository/REST`

### Step 2
`Emergency WebSocket`

### Step 3
`실제 EmailSender`

### Step 4
`V9 migration: calibration + input_session_log + recommendation state + patient_alias`

### Step 5
`Calibration API`

### Step 6
`InputSession API`

### Step 7
`Recommendation` — 추천 정의서 확정 후

### Step 8
`patient_alias API + STAFF-002 + Chat 표시명`

### Step 9
`전체 regression + Swagger + docs`

---

# 25. 완료 기준

- V1~V7 무수정
- V8/V9 pristine migration 성공
- Emergency REST 3종 정상
- Emergency WebSocket 정상
- 이메일 실발송 경로 존재
- Calibration 저장/active 조회 정상
- InputSession 저장/조회 정상
- 실제 규칙 기반 Recommendation 정상(규칙 확정 시)
- retestOptOut 영속
- STAFF patient_alias 수정 API 정상
- 의료진별 alias 격리 정상
- 일반 `userName -> loginId` 규칙 유지
- 기존 Chat/E2EE 회귀 없음
- 기존 41 REST API 회귀 없음
- OpenAPI 정상
- 전체 test green
- temporary code 없음
- secret commit 없음

---

## 부록 — 핵심 흐름

### Emergency

```text
PATIENT
  -> POST /api/emergency-calls
Backend
  -> emergency_call INSERT
DB COMMIT
  -> 담당 STAFF 조회
  -> /user/queue/emergency
```

### Calibration -> InputSession -> Recommendation

```text
Browser Calibration
  -> 최종 보정 parameter
  -> POST /api/calibrations
calibration
  -> 초기 입력 검사
  -> Frontend 지표 계산
  -> POST /api/input-sessions
input_session_log
  -> RecommendationPolicy
  -> user_setting.recommended_input_method
```

### 의료진별 환자 이름

```text
users.user_name = 김철수

STAFF A assignment.patient_alias = 101호_김철수
STAFF B assignment.patient_alias = NULL

환자 화면 -> 김철수
STAFF A  -> 101호_김철수
STAFF B  -> 김철수
```

## 팀 확인이 필요한 두 항목

1. Recommendation: 지표별 가중치, threshold, 최소 검사 데이터 수, 동점 처리, 재추천 조건
2. Chat 권한: STAFF 담당환자 한정 여부, PATIENT의 STAFF 접근 범위, 배정 해제 후 room/조회/전송 정책

위 두 항목은 확정 전 Claude가 임의로 구현하지 않는다.
