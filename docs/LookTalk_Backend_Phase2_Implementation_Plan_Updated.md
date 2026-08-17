# **LookTalk Backend 2차 작업 정리안**

> 목적: 현재 V1~V8 schema와 기존 REST/WebSocket 구현을 기준선으로 유지하면서 Calibration, InputSession, 입력방식 Recommendation, 의료진별 환자 별칭을 확장한다. Frontend의 `docs/LookTalk_Frontend_VirtualKeyboard_Integration_Plan.md`와 Calibration/InputMethod 계약을 동일하게 유지하며, Claude는 실제 Repository를 먼저 확인한 뒤 Step 단위로 구현·검증한다.
>
> 현재 구현 상태(2026-08-17 기준): **Step 1 V8 + Emergency REST, Step 2 Emergency WebSocket, Step 3 실제 SMTP EmailSender까지 완료**. 전체 `./gradlew clean test`는 49/49 green 상태이며, **다음 작업은 Step 4 V9 migration**이다. 아직 commit/push하지 않은 작업 트리 기준이므로 구현 전 `git status --short`를 다시 확인한다.

---

## **1. 기본 원칙**

- V1~V7 migration은 절대 수정하지 않는다.
- Step 1에서 검증 완료된 V8도 V9 요구를 섞어 다시 수정하지 않는다. 이후 DB 변경은 **V9 신규 migration**으로만 추가한다.
- 실제 SQL, Entity, Repository, Service가 문서와 다르면 실제 Repository를 우선 확인하되, 이미 확정된 제품 계약을 임의 변경하지 않는다.
- 기존 `ApiResponse<T>`, `ErrorCode`, Security, OpenAPI, CORS 구조를 유지한다.
- PATIENT/STAFF 권한은 Security URL rule과 Service에서 모두 재검증한다.
- 기존 API field를 rename/remove하지 않는다. 신규 field는 backward-compatible하게 추가한다.
- mock 추천, fake SMS 성공, raw OTP 로그, raw biometric frame 저장을 금지한다.
- 기존 일반 표시명 규칙 `userName -> loginId`는 유지하고 UUID `userId`를 표시명으로 사용하지 않는다.
- Calibration은 **입력 방식별 별도 보정값이 아니라 사용자별 공통 gaze mapping**이다.
- `UserSetting.currentInputMethod`는 Calibration과 별개이며, `EYE_TRACKING | BLINK | MOUTH` 중 현재 실제 confirm 방식을 의미한다.
- Frontend 1차 gaze mapping은 **RAW_HOMOGRAPHY + NORMALIZED_VIEWPORT**만 사용한다. head-pose/SQPnP/ridge/L2CS 데이터를 Backend schema나 API에 추가하지 않는다.
- Frontend에서 측정한 candidate calibration은 메모리에만 유지하며, 사용자가 결과 모달에서 입력 방식을 실제 선택한 뒤 `POST /api/calibrations`가 호출될 때만 Backend에 저장한다.
- Backend의 `Recommendation`은 **입력 방식 추천(InputMethodRecommendation)**만 의미한다. 향후 NLP `WordSuggestion`과 혼동하지 않는다.
- Frontend와 맞물리는 계약은 `looktalk-frontend/docs/LookTalk_Frontend_VirtualKeyboard_Integration_Plan.md`를 함께 확인한다.

## **2. 2차 작업 최종 범위**

### **구현 상태/범위**

| 구분 | 작업 | 상태 |
|---|---|---|
| V8 | `emergency_call` 신설 | 완료 |
| V8 | `chat_room.room_type` CHECK 보강 | 완료 |
| V8 | `input_session_log.user_id` index 추가 | 완료 |
| Emergency | 호출 POST / 담당 호출 GET / 읽음 PATCH | 완료 |
| WebSocket | `/user/queue/emergency` 추가 | 완료 |
| Email | 실제 SMTP `EmailSender` 구현체 + 배포 fail-fast | 완료 |
| V9 | 사용자별 공통 `calibration` 테이블 신설 | 다음 작업 |
| V9 | `input_session_log` 분석용 컬럼/제약/FK 보강 | 예정 |
| V9 | `user_setting.recommended_input_method`, `recommended_at` 추가 | 예정 |
| V9 | `staff_patient_assignment.patient_alias` 추가 | 예정 |
| Calibration | accepted calibration 저장 + active 조회 API | 예정 |
| InputSession | 검사 결과 저장 + 목록/최근 결과 조회 API | 예정 |
| Recommendation | 실제 확정 규칙 기반 입력방식 추천 + 상태 조회 | 규칙 확정 후 |
| Staff | 담당 환자 별칭 수정 API | 예정 |
| Chat 표시명 | STAFF가 자기 담당 환자를 볼 때 자기 alias 우선 적용 | 예정 |

### **이번 단계에서 추가하지 않음**

- `users.hospital_display_name`: 환자가 일반 이름과 병원용 이름을 별도로 설정하는 UI가 확정될 때만 추후 migration에서 추가한다. 현재 요구는 `user_name` + 의료진별 `patient_alias`로 해결한다.
- `users.privacy_agreed_at`: 실제 회원가입 동의 Request/저장 흐름이 확정되기 전에는 NULL 컬럼만 미리 추가하지 않는다.
- `user_setting.retest_opt_out`: 현재 Frontend UX에는 “재검사 거부/다시 보지 않기” 기능이 없다. 분석 페이지에서 사용자가 재측정을 직접 시작하므로 V9에 dormant column/API를 만들지 않는다.
- NLP 단어 추천(`WordSuggestion`): 추천 단어 UI 공간만 고려하고, NLP 모델/정책이 확정되기 전에는 Backend `Recommendation`에 섞지 않는다.
- head-pose, `pose_corrected`, `sqpnp_corrected`, `ridge_hybrid`, L2CS 관련 Calibration 저장 필드/API.
- 천지인 관련 Backend 제약 추가: Frontend 1차는 QWERTY만 사용하지만 기존 `keyboard_layout` 계약을 불필요하게 좁히지 않는다.
- SMS Provider + §59 inbound: Provider 선정/발신번호 승인 이후 별도 작업. 현재 503 동작을 fake success로 바꾸지 않는다.
- 로그인 상태 비밀번호 변경 API: 공모전 이후.

## **3. 구현 순서**

1. ✅ Repository 실제 상태 재확인
2. ✅ V8 migration
3. ✅ Emergency REST
4. ✅ Emergency WebSocket
5. ✅ 이메일 실발송 + profile/provider fail-safe
6. **입력 지표 계약 재확인** — V9 migration 자체는 가능하지만 InputSession/Recommendation Service 구현 전 산식·단위 확정
7. **V9 migration** — 다음 작업
8. Calibration API
9. InputSession API
10. Recommendation — 실제 추천 규칙 확정 후
11. `patient_alias` API + STAFF/Chat 표시명 반영
12. 전체 regression + Swagger + docs

각 단계 완료 후 compile/test/DB 검증을 한 뒤 다음 단계로 진행한다.

Step 4(V9)부터는 Frontend 문서의 다음 계약을 변경하지 않는다.

```text
공통 gaze calibration 1개 / user
RAW_HOMOGRAPHY
NORMALIZED_VIEWPORT
Calibration != currentInputMethod
candidate는 사용자 선택 전 Backend 저장 금지
InputMethodRecommendation != WordSuggestion
```

## **4. Phase 0 — V9 구현 전 반드시 확인**

### **Migration**

`src/main/resources/db/migration/V1~V8`을 직접 읽고 다음을 확인한다.

- V1~V7 checksum/내용이 변경되지 않았는지
- V8이 Step 1 최종본 그대로인지
- `users.user_id` 실제 type
- `staff.staff_id` 실제 type
- `staff_patient_assignment` 현재 column/FK/UNIQUE
- `input_session_log` 현재 column, PK type
- **`input_session_log.calibration_id VARCHAR(100)`이 이미 존재하는지 재확인**
- `input_session_log.calibration_id`에 FK가 아직 없는지
- `input_session_log.input_method` 현재 constraint
- `user_setting` 현재 column
- `chat_room.room_type` 현재 CHECK

### **Java**

최소 다음을 읽는다.

- `ErrorCode`
- `SecurityConfig`
- `DisplayNameResolver`
- `UserSetting` Entity/DTO/Service
- `StaffPatientAssignment` Entity/Repository
- `StaffService`, `StaffController`
- `ChatContactService`, `ChatRoomService`, `ChatMessageService`
- `EmailSender`, `LoggingEmailSender`, `SmtpEmailSender`
- `application.yml`, `application-local.yml`, `build.gradle`
- Emergency Step 1~2 최종 구현

Frontend sibling repository에 접근 가능하면 반드시 다음도 읽는다.

```text
looktalk-frontend/docs/LookTalk_Frontend_VirtualKeyboard_Integration_Plan.md
```

특히 확인할 Front 계약:

```text
mappingType = RAW_HOMOGRAPHY
coordinateSpace = NORMALIZED_VIEWPORT
mirrorStrategy
calibratedViewport metadata
result modal: 입력 방식 선택 또는 재측정, 닫기 없음
Calibration과 currentInputMethod 분리
```

작업 전 실행:

```bash
git status --short
./gradlew clean test
```

현재 Step 3 기준선은 **11개 테스트 클래스 / 49 tests green**이다. 기존 테스트가 깨져 있으면 V9부터 구현하지 않는다.

# **5. V8 — Emergency + 무결성 보강**

예상 migration:

```text
V8__add_emergency_and_integrity_constraints.sql
```

## **5.1** `emergency_call`

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

## **5.2** `chat_room.room_type` **CHECK**

허용값을 Java enum과 동일하게 고정한다.

```text
HOSPITAL
SMS
```

기존 constraint가 이미 있으면 중복 생성하지 않는다.

## **5.3** `input_session_log.user_id` **index**

```text
ix_input_session_log_user_id(user_id)
```

실제 V7 schema를 확인하고 추가한다.

---

# **6. Emergency REST**

패키지는 기존 convention에 맞춰 `emergency/controller,dto,entity,repository,service,websocket` 구조로 추가한다.

## **EMERGENCY-001 호출 발생**

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

## **EMERGENCY-002 담당 환자 호출 조회**

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

## **EMERGENCY-003 읽음 처리**

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

# **7. Emergency WebSocket**

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

# **8. 이메일 실발송 — Step 3 구현 완료 기준**

기존 `EmailSender` interface는 유지한다.

```text
EmailSender
├─ LoggingEmailSender   # local 전용 기본
└─ SmtpEmailSender      # 실제 발송
```

구현 확정 사항:

- `JavaMailSender` 기반 `SmtpEmailSender`를 사용한다.
- `MAIL_PROVIDER=logging|smtp`로 구현체를 선택한다.
- Base `application.yml`의 `looktalk.mail.provider`는 `${MAIL_PROVIDER}`로 **기본값을 두지 않는다.**
- Base `application.yml`에서 `spring.profiles.active/default`로 local profile을 강제하지 않는다.
- `application-local.yml`에서만 `looktalk.mail.provider: ${MAIL_PROVIDER:logging}` 기본값을 제공한다.
- `LoggingEmailSender`는 `havingValue="logging"`일 때만 등록하며 `matchIfMissing=true`를 사용하지 않는다.
- `./gradlew test`는 Gradle test task에서 `SPRING_PROFILES_ACTIVE=local`을 테스트 JVM에만 주입한다.
- 실제 local `bootRun`은 개발자가 `SPRING_PROFILES_ACTIVE=local`을 명시한다.
- non-local/profile 미설정 배포에서 `MAIL_PROVIDER`를 빠뜨리면 애플리케이션이 기동 실패해야 하며 logging으로 silent fallback하면 안 된다.
- `MAIL_PROVIDER=smtp`일 때 `MAIL_HOST`, `MAIL_FROM`은 필수다.
- `MAIL_SMTP_AUTH=true`이면 `MAIL_USERNAME`, `MAIL_PASSWORD`도 필수다.
- 필수 SMTP 설정 누락은 Bean 생성 단계에서 fail-fast한다.
- 실제 발송 실패는 `EMAIL_SEND_FAILED` 502로 처리한다.
- AUTH-001/002 API contract와 OTP transaction은 변경하지 않는다.
- SMTP 발송 예외 시 같은 transaction의 verification code INSERT가 rollback된다.
- 인증번호 원문, 비밀번호, SMTP credential을 로그에 남기지 않는다.

환경변수:

```text
MAIL_PROVIDER
MAIL_HOST
MAIL_PORT
MAIL_USERNAME
MAIL_PASSWORD
MAIL_FROM
MAIL_SMTP_AUTH
MAIL_STARTTLS_ENABLE
```

Step 3에서 실제 검증된 boot 시나리오:

```text
A. profile 미설정 + MAIL_PROVIDER 미설정 -> 기동 실패
B. local + MAIL_PROVIDER 미설정         -> LoggingEmailSender 정상
C. non-local + smtp + 필수 설정         -> SmtpEmailSender 정상
D. non-local + MAIL_PROVIDER 미설정      -> 기동 실패
```

이메일 관련 구조는 V9에서 다시 설계하지 않는다.

# **9. 입력 지표 계약 — InputSession/Recommendation 코드 전 필수**

InputSession에 저장할 후보 지표:

```text
typoRate
inputSpeed
recognitionAccuracy
inputStability
```

팀/Frontend와 확정해야 하는 것:

- 각 metric 산식
- `inputSpeed` 단위
- 측정 시작/종료 시점
- 값 범위
- null 정책
- 입력 방식별 측정 방식
- Recommendation 가중치/threshold/최소 데이터 수/동점 처리/재추천 조건

Backend가 임의로 지표 산식이나 추천 threshold를 만들면 안 된다.

현재 validation 후보:

```text
typoRate             0.0 ~ 1.0
recognitionAccuracy  0.0 ~ 1.0
inputStability       0.0 ~ 1.0
inputSpeed           0 이상
```

단, 위 범위도 실제 Front 계산 계약과 대조한 뒤 확정한다.

Calibration의 `reprojectionRmseNormalized`는 **gaze Homography 자체의 보정 품질 metadata**이며 위 InputSession metric이나 `qualityScore`와 동일한 값으로 취급하지 않는다. 별도의 0~1 `quality_score` 변환식을 Backend가 임의로 만들지 않는다.

InputSession에는 raw frame, raw landmark, frame별 iris/gaze sequence를 저장하지 않는다.

# **10. V9 — Calibration / InputSession / Recommendation / patient_alias**

예상 migration:

```text
V9__add_calibration_analysis_and_patient_alias.sql
```

## **10.1 `calibration` 신설**

### 의미

Calibration은 사용자별 **공통 gaze mapping**이다.

```text
iris x/y
  -> RAW_HOMOGRAPHY
  -> NORMALIZED_VIEWPORT x/y
```

`EYE_TRACKING`, `MOUTH`, 향후 `BLINK`가 각각 별도 Homography를 갖는 구조가 아니다. 입력 방식은 `user_setting.current_input_method` 및 `input_session_log.input_method`에서 별도로 관리한다.

### 저장 금지

- 웹캠 원본 영상/프레임
- 눈 이미지
- 프레임별 raw landmark
- 프레임별 raw iris/gaze sequence
- head pose / pose baseline
- SQPnP 결과
- ridge coefficient / 18차원 feature
- L2CS 관련 값
- Frontend candidate 전용 `createdAtLocal`

### 논리 schema

| column | 규칙 |
|---|---|
| `calibration_id` | PK, Backend UUID 문자열 |
| `user_id` | NOT NULL FK -> users, ON DELETE CASCADE |
| `algorithm_version` | VARCHAR(100) NOT NULL |
| `calibration_data` | JSONB NOT NULL |
| `created_at` | TIMESTAMPTZ NOT NULL |
| `revoked_at` | TIMESTAMPTZ NULL |

`user_id` type은 실제 `users.user_id`와 정확히 맞춘다.

이번 V9에서는 `input_method`, generic `quality_score`, 별도 `viewport_width/height/device_pixel_ratio` 컬럼을 만들지 않는다. Frontend v1 calibration payload가 self-contained metadata를 `calibration_data`에 포함하며, 서버에서 indexed query할 요구가 생길 때 별도 migration으로 승격한다. 정의되지 않은 0~1 quality score를 임의 생성하지 않는다.

### active invariant

사용자별 active gaze calibration은 최대 1개다.

```text
revoked_at IS NULL
```

권장 DB 방어:

```text
UNIQUE partial index on calibration(user_id) WHERE revoked_at IS NULL
```

새 calibration 저장 transaction에서는 사용자 단위 lock 또는 동등한 직렬화 방식으로:

```text
현재 active revoke
-> 신규 row insert
```

를 처리하고 partial unique index를 최종 안전망으로 둔다.

**신규 POST 자체가 실패하면 기존 active는 revoke되지 않아야 한다.**

### calibration_data v1 계약

Frontend 문서와 맞춘 v1 envelope:

```json
{
  "schemaVersion": 1,
  "mappingType": "RAW_HOMOGRAPHY",
  "coordinateSpace": "NORMALIZED_VIEWPORT",
  "homography": [
    [1.0, 0.0, 0.0],
    [0.0, 1.0, 0.0],
    [0.0, 0.0, 1.0]
  ],
  "mirrorX": true,
  "mirrorStrategy": "PRE_INFERENCE_FRAME_FLIP",
  "grid": {
    "rows": 4,
    "cols": 4,
    "margin": 0.08
  },
  "calibratedViewport": {
    "widthPx": 1920,
    "heightPx": 1080,
    "aspectRatio": 1.7777777778,
    "orientation": "landscape"
  },
  "reprojectionRmseNormalized": 0.012
}
```

`mirrorStrategy` 허용값:

```text
PRE_INFERENCE_FRAME_FLIP
POST_INFERENCE_LANDMARK_FLIP
```

`orientation` 허용값:

```text
portrait
landscape
```

Backend는 Homography를 다시 학습하거나 gaze를 재계산하지 않는다. 다만 `schemaVersion=1`에 대해 **계약 위반/개인정보 오저장 방지를 위한 구조 validation**은 수행한다.

최소 validation:

- `schemaVersion == 1`
- `mappingType == RAW_HOMOGRAPHY`
- `coordinateSpace == NORMALIZED_VIEWPORT`
- `homography`가 정확히 3x3 finite numeric matrix
- `mirrorX == true`
- `mirrorStrategy`가 허용 enum
- `grid.rows == 4`, `grid.cols == 4`, `grid.margin == 0.08`
- `calibratedViewport.widthPx > 0`, `heightPx > 0`, `aspectRatio > 0`
- `orientation` 허용값
- `reprojectionRmseNormalized`가 있으면 0 이상
- schema v1에서 정의하지 않은 raw frame/landmark/sequence payload를 저장하지 않음

구조가 잘못되면 `CALIBRATION_INVALID_PAYLOAD` 400을 사용한다.

Viewport/aspect-ratio compatibility의 구체 임계값은 Backend가 정하지 않는다. Frontend가 저장된 `calibratedViewport`와 현재 viewport를 비교하여 QA 기반으로 `compatible/uncertain/incompatible`를 판정한다.

## **10.2 `input_session_log` 보강**

실제 V1~V8 schema에 없는 것만 추가한다.

### 기존 `calibration_id`

`input_session_log.calibration_id VARCHAR(100)`은 이미 존재한다.

따라서 V9에서는 **컬럼을 다시 ADD하지 않는다.**

대신 기존 데이터 상태를 확인한 뒤:

```text
input_session_log.calibration_id
  -> calibration.calibration_id
```

nullable FK만 추가한다.

Calibration은 정상 흐름에서 revoke이지 delete다. 예외적으로 calibration row가 삭제되는 경우 과거 log가 사용자 삭제/정리 흐름을 막지 않도록 FK의 `ON DELETE` 정책은 실제 USER 삭제 cascade와 함께 검토한다. 기본 후보는 `ON DELETE SET NULL`이다.

기존 non-null `calibration_id`가 있는데 대응 calibration row가 없는 데이터가 존재하면 임의 NULL 처리하지 말고 migration 전에 보고한다.

### `input_method` CHECK

```text
EYE_TRACKING
BLINK
MOUTH
```

### `session_type`

```text
CALIBRATION_TEST
NORMAL_USE
```

기존 row가 있으면 실제 historical 의미를 확인한 뒤 안전하게 backfill한다. 데이터 의미를 모른 채 전부 `CALIBRATION_TEST`로 덮지 않는다.

### calibration 참조 정책

현재 제품에서는 세 confirm 방식이 공통 gaze mapping을 사용한다.

```text
session_type = CALIBRATION_TEST -> calibrationId 필수
session_type = NORMAL_USE       -> calibrationId nullable
```

따라서 `EYE_TRACKING`만 특별 취급하지 않는다. 향후 BLINK 설계가 완전히 다른 모델로 확정되면 별도 migration/API version에서 다시 결정한다.

`calibrationId`가 있으면 현재 로그인 사용자의 calibration인지 확인한다. 생성 시 active 여부를 강제하지 않아도 되며, 과거/revoked calibration을 참조한 지표도 historical record로 유지할 수 있다.

`log_id`는 현재 PK type을 유지하고 Frontend가 보내지 않는다.

```java
UUID.randomUUID().toString()
```

으로 Backend가 생성한다.

## **10.3 `user_setting`**

추가:

```text
recommended_input_method NULL
recommended_at TIMESTAMPTZ NULL
```

`recommended_input_method` CHECK는 기존 `InputMethod` enum과 동일:

```text
EYE_TRACKING
BLINK
MOUTH
```

역할:

```text
current_input_method      = 사용자가 실제 선택해 현재 사용하는 confirm 방식
recommended_input_method  = 최근 확정된 검사/정책 기반 추천 방식
```

추천이 생겨도 `current_input_method`를 자동 변경하지 않는다.

이번 V9에서는 `retest_opt_out`을 추가하지 않는다.

## **10.4 `staff_patient_assignment.patient_alias`**

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

# **11. Calibration API**

PATIENT 전용이다.

Calibration API는 입력 방식을 받지 않는다. 하나의 사용자에게 하나의 active gaze calibration만 존재한다.

## **CAL-001 accepted calibration 저장**

```http
POST /api/calibrations
```

Frontend에서는 16점 측정 직후 바로 호출하지 않는다.

```text
16점 측정
-> candidate를 Front memory에 유지
-> 측정 결과 모달
   ├─ 재측정: candidate 폐기, Backend 호출 없음
   └─ 입력 방식 선택: 이때 CAL-001 호출
```

Request 예시:

```json
{
  "algorithmVersion": "raw-homography-v1",
  "calibrationData": {
    "schemaVersion": 1,
    "mappingType": "RAW_HOMOGRAPHY",
    "coordinateSpace": "NORMALIZED_VIEWPORT",
    "homography": [
      [1.0, 0.0, 0.0],
      [0.0, 1.0, 0.0],
      [0.0, 0.0, 1.0]
    ],
    "mirrorX": true,
    "mirrorStrategy": "PRE_INFERENCE_FRAME_FLIP",
    "grid": {
      "rows": 4,
      "cols": 4,
      "margin": 0.08
    },
    "calibratedViewport": {
      "widthPx": 1920,
      "heightPx": 1080,
      "aspectRatio": 1.7777777778,
      "orientation": "landscape"
    },
    "reprojectionRmseNormalized": 0.012
  }
}
```

Response data 권장:

```json
{
  "calibrationId": "uuid-string",
  "algorithmVersion": "raw-homography-v1",
  "calibrationData": {},
  "createdAt": "..."
}
```

처리:

1. PATIENT/JWT userId 재검증
2. `algorithmVersion`/`calibrationData` validation
3. Backend UUID 생성
4. 사용자 단위 동시성 보호
5. 기존 active가 있으면 revoke
6. 신규 calibration insert
7. commit 후 response

새 POST validation/insert가 실패하면 기존 active는 유지한다.

`calibrationData`를 Backend가 gaze mapping으로 실행하거나 Homography를 재학습하지 않는다.

### UserSetting과의 관계

사용자가 결과 모달에서 `EYE_TRACKING|MOUTH|BLINK`를 선택한 Front 흐름은:

```text
CAL-001 POST 성공
-> SET-002 PATCH currentInputMethod
-> 둘 다 성공하면 완료 모달
```

두 API를 하나의 Backend transaction으로 합치지 않는다. Frontend가 `Calibration POST 성공 + UserSetting PATCH 실패` 시 받은 `calibrationId`를 보존하고 PATCH만 재시도한다.

Calibration은 공통 gaze mapping이므로 PATCH 실패로 예전 `currentInputMethod`가 남아 있어도 새 calibration과 의미상 충돌하지 않는다.

## **CAL-002 active 조회**

```http
GET /api/calibrations/active
```

query parameter `inputMethod`를 사용하지 않는다.

- 본인 active gaze calibration만 조회
- `calibrationData` 전체 round trip
- 없으면 `CALIBRATION_NOT_FOUND` 404
- 401/403/5xx를 “없음”으로 바꾸지 않는다

Frontend는 새로고침/재로그인 시 이 API를 먼저 호출하고 active가 있으면 재사용한다. `calibratedViewport` 호환성 판정은 Frontend가 수행하며, 호환성이 uncertain/incompatible이라고 해서 Backend가 active를 자동 revoke하지 않는다.

# **12. InputSession API**

PATIENT 전용이다.

InputSession은 raw frame 로그가 아니라 **Frontend가 계산한 요약 성능 metric** 저장용이다.

## **INPUT-001 저장**

```http
POST /api/input-sessions
```

Request 예시:

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
2. enum/metric validation
3. `calibrationId`가 있으면 현재 사용자 소유인지 확인
4. `sessionType=CALIBRATION_TEST`인데 `calibrationId`가 없으면 `CALIBRATION_REQUIRED` 409
5. Backend가 `logId` UUID 생성
6. JWT userId로 저장
7. 실제 추천 규칙이 준비된 경우에만 RecommendationService 재계산

다른 사용자의 `calibrationId`는 존재 노출을 피하기 위해 `CALIBRATION_NOT_FOUND`로 처리한다.

Calibration이 입력 방식별로 분리되지 않으므로 `EYE_TRACKING`, `MOUTH`, 향후 `BLINK` 테스트가 동일 calibration을 참조할 수 있다.

지표 산식/단위/null 정책이 확정되기 전에는 Backend가 값을 변환하거나 계산하지 않는다.

## **INPUT-002 목록/필터**

```http
GET /api/input-sessions?inputMethod=EYE_TRACKING&sessionType=CALIBRATION_TEST&page=0&size=20
```

- 현재 로그인 PATIENT log만
- filter optional
- `session_at DESC` + 안정적인 PK 정렬
- 데이터 없음은 정상 empty list

## **INPUT-003 최근 결과**

```http
GET /api/input-sessions/latest?inputMethod=EYE_TRACKING&sessionType=CALIBRATION_TEST
```

없으면 `INPUT_SESSION_NOT_FOUND` 404. Frontend 분석 empty state에서 처리한다.

# **13. Recommendation — 입력방식 추천만**

Backend의 Recommendation은 **InputMethodRecommendation**이다.

```text
EYE_TRACKING
BLINK
MOUTH
```

중 검사 metric 기반으로 어떤 confirm 방식을 추천할지 결정한다.

향후 NLP 단어 자동완성은 `WordSuggestion`이며 이 도메인과 완전히 별개다.

추천 threshold/가중치/최소 데이터 수/동점 처리/재추천 조건이 확정되기 전에는 임의 추천을 구현하지 않는다.

권장 구조:

```text
RecommendationService
        ↓
InputMethodRecommendationPolicy
        ↓
팀이 확정한 실제 deterministic 규칙 구현체
```

추천 결과가 나오면:

```text
user_setting.recommended_input_method
user_setting.recommended_at
```

만 갱신한다.

```text
current_input_method
```

은 사용자가 결과 모달에서 실제로 선택한 값이며 Recommendation이 자동 변경하면 안 된다.

예:

```text
추천 = EYE_TRACKING
사용자 선택 = MOUTH
currentInputMethod = MOUTH
```

이어야 한다.

## **REC-001 추천 상태 조회**

```http
GET /api/users/me/input-recommendation
```

Response 예시:

```json
{
  "currentInputMethod": "MOUTH",
  "recommendedInputMethod": "EYE_TRACKING",
  "recommendedAt": "...",
  "hasRecommendation": true,
  "changed": true
}
```

추천이 없으면 404보다:

```json
{
  "currentInputMethod": "EYE_TRACKING",
  "recommendedInputMethod": null,
  "recommendedAt": null,
  "hasRecommendation": false,
  "changed": false
}
```

형태의 200을 사용한다.

현재 UX에는 `retestOptOut`가 없으므로 `REC-002 /input-recommendation/preferences` API를 구현하지 않는다.

실제 Recommendation API가 준비되지 않은 production Frontend는 추천값을 mock/random으로 만들어 “AI 추천”처럼 표시하면 안 된다.

# **14. 의료진별 환자 별칭 API**

기존 전역 `DisplayNameResolver`는 그대로 둔다.

```text
일반 표시명 = userName -> loginId
```

STAFF가 자기 담당 PATIENT를 보는 문맥에서만:

```text
patientAlias -> userName -> loginId
```

별도 `StaffPatientDisplayNameResolver` 같은 helper를 둔다. helper 내부에서 목록마다 반복 DB query를 만들지 말고 Service가 assignment를 조회해 alias를 전달하는 방향을 우선한다.

## **STAFF-005 alias 수정**

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

# **15. patient_alias의 Chat 적용**

요구: STAFF A가 환자 김철수를 `101호_김철수`로 설정하면 ****STAFF A가 보는 병원 채팅에서만**** 해당 이름을 사용한다.

## **CHAT-002 room list**

현재 사용자=STAFF, 상대=담당 PATIENT이면:

```text
target.displayName = patientAlias -> userName -> loginId
```

PATIENT가 같은 room을 볼 때 STAFF alias를 반환하면 안 된다.

## **CHAT-005 message history**

현재 사용자=STAFF이고 sender가 자기 담당 PATIENT이면 alias-aware `senderDisplayName`을 반환한다.

- PATIENT에게 STAFF alias 노출 금지
- withdrawn sender의 `탈퇴한 사용자` 유지
- 외부 SMS snapshot 이름 유지

## **WebSocket** `MESSAGE_CREATED`

PATIENT -> STAFF 이벤트의 `senderDisplayName`은 ****recipient STAFF 자신의 alias****를 반영한다. 다른 recipient에게 같은 alias를 재사용하지 않는다. 기존 per-recipient ciphertext isolation 원칙을 유지한다.

## **CHAT-001 contact list**

이번 alias 작업으로 Chat 대상 eligibility를 바꾸지 않는다. assignment가 있는 PATIENT의 `displayName`만 alias-aware하게 할 수 있다.

`patient_alias` keyword 검색 및 STAFF 채팅 대상 제한은 Chat 정책 확정 후 함께 수정한다.

---

# **16. 아직 확정되지 않은 Chat 정책 — 임의 구현 금지**

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

# **17. 비밀번호 정책**

이번 2차 Backend 작업에서 기존 `8~64자` validation을 변경하지 않는다.

와이어프레임의 `4~6자리` 문구만으로 Backend를 낮추지 않는다. 정말 4~6자리 간편 인증이 필요하면 별도 PIN 기능으로 설계한다.

---

# **18. 신규 ErrorCode 후보**

실제 `ErrorCode`를 먼저 읽고 중복 의미가 없는 경우에만 추가한다.

```text
EMERGENCY_CALL_NOT_FOUND       404
CALIBRATION_NOT_FOUND          404
CALIBRATION_REQUIRED           409
CALIBRATION_INVALID_PAYLOAD    400
INPUT_SESSION_NOT_FOUND        404
```

Alias는 기존 `STAFF_PATIENT_NOT_ASSIGNED` 404를 재사용한다. Bean Validation은 기존 `COMMON_VALIDATION_ERROR` 흐름을 사용한다.

---

# **19. Security 권한**

논리 권한:

```text
POST  /api/emergency-calls/**             -> PATIENT
GET   /api/emergency-calls/**             -> STAFF
PATCH /api/emergency-calls/**             -> STAFF
/api/calibrations/**                       -> PATIENT
/api/input-sessions/**                     -> PATIENT
/api/users/me/input-recommendation/**      -> PATIENT
/api/staff/**                              -> 기존 STAFF rule
```

실제 matcher는 현재 `SecurityConfig` 구조를 읽고 적용한다. Service role check도 유지한다.

---

# **20. 필수 테스트**

## **Migration**

- V1~V7 diff/checksum 없음
- Step 1 V8 내용 변경 없음
- pristine DB에서 V1~V8 성공
- pristine DB에서 V1~V9 성공
- Hibernate validate 성공
- `input_session_log.calibration_id`를 중복 ADD하지 않음
- 기존 `calibration_id` FK 추가가 실제 데이터와 충돌하지 않음

## **Email Step 3 regression**

- profile 미설정 + MAIL_PROVIDER 미설정 -> 기동 실패
- local + MAIL_PROVIDER 미설정 -> LoggingEmailSender
- non-local + smtp + 필수 설정 -> SmtpEmailSender
- non-local + MAIL_PROVIDER 미설정 -> 기동 실패
- SMTP auth=true인데 username/password 누락 -> 기동 실패
- SMTP auth=false이면 username/password 없이 기동 가능
- 전체 기존 mail/Auth 테스트 유지

## **Emergency**

- PATIENT 호출 성공 / STAFF 호출 403 / 미인증 401
- 담당 STAFF만 조회
- 미담당 STAFF 호출 노출 없음
- unread filter
- 최초 read만 저장
- 동시 read에서도 최초 reader 보존
- 읽은 STAFF 탈퇴 후 call 보존
- 담당 STAFF 0명 호출 저장 성공

## **Emergency WebSocket**

- JWT 없는 CONNECT 실패
- `/user/queue/emergency` 구독 성공
- 임의 destination 구독 실패
- 담당 STAFF만 이벤트 수신
- publish 실패가 REST/DB commit을 무효화하지 않음
- `/user/queue/chat` regression 없음

## **Calibration**

- PATIENT 전용
- server UUID
- `inputMethod` 없이 저장/조회
- v1 `RAW_HOMOGRAPHY` payload 정상 round trip
- `NORMALIZED_VIEWPORT` 외 값 거부
- homography 3x3 shape/numeric validation
- mirrorStrategy enum validation
- calibratedViewport validation
- raw/biometric-like 금지 payload 저장 방지
- 신규 accepted 저장 시 기존 active revoke
- **신규 저장 실패 시 기존 active 유지**
- 사용자별 active 최대 1개
- concurrent 저장에서도 active 최대 1개
- cross-user 접근 차단
- GET active 없음 -> `CALIBRATION_NOT_FOUND`
- revoked historical row 유지

## **InputSession**

- server logId
- V9에서 기존 calibration_id column 재생성 안 함
- cross-user calibration 차단
- `CALIBRATION_TEST`는 inputMethod와 무관하게 calibrationId 필수
- `NORMAL_USE`는 calibrationId nullable
- revoked calibration historical reference 허용
- 수치 validation
- 본인 log만 조회
- filter/empty/latest 정상

## **Recommendation**

- NLP WordSuggestion과 분리
- mock/random 추천 없음
- deterministic 실제 규칙
- currentInputMethod 자동 변경 안 함
- recommended 상태 저장
- 추천 없음은 200 + `hasRecommendation=false`
- `retestOptOut`/REC-002가 없음

## **patient_alias**

- 자기 담당 환자만 수정
- 50자 validation
- null/blank 삭제
- 다른 STAFF 수정 불가
- 환자 `user_name` 불변
- STAFF A alias가 STAFF B/PATIENT에게 노출되지 않음
- STAFF-002, CHAT-002, CHAT-005, WebSocket recipient별 표시 정상

# **21. 전체 회귀 검증**

Step 3 완료 기준선:

```text
11 test classes
49 tests
0 failures
0 errors
```

V9 이후 마지막에 반드시:

```bash
./gradlew clean test
```

및 실제 boot/migration 검증을 수행한다.

기존 Auth/User/Staff/E2EE/Memo/Phrase/Friend/CHAT-001~006/WebSocket/USER-005/Emergency/Email이 깨지지 않아야 한다.

특히:

- `patient_alias` 때문에 전역 `DisplayNameResolver`를 변경해서 기존 사용자 표시가 바뀌면 안 된다.
- Calibration에서 `input_method`를 다시 도입해 Front의 공통 gaze calibration 계약을 깨면 안 된다.
- `calibration_data` schema를 임의의 ridge/head-pose 구조로 바꾸면 안 된다.
- Email profile/provider fail-safe를 V9 config 수정 과정에서 되돌리면 안 된다.

# **22. Swagger / 문서**

실제 구현 후 갱신:

```text
docs/LookTalk_Backend_API_Spec_v2.1.md
docs/LookTalk_Backend_Implementation.md
docs/database.md
```

Swagger에서 신규 API의 bearerAuth, request/response schema, enum, nullable, error schema를 확인한다. 기존 `CommonErrorResponseCustomizer`를 깨지 않는다.

---

# **23. Claude 실행 규칙**

각 Step 시작 전:

1. 관련 기존 SQL/코드를 읽는다.
2. 이 문서와 실제 구조 충돌 여부를 먼저 보고한다.
3. Frontend 계약이 관련되면 `looktalk-frontend/docs/LookTalk_Frontend_VirtualKeyboard_Integration_Plan.md`도 확인한다.
4. 수정 대상 파일 목록을 제시한다.
5. 정책 결정이 필요한 부분은 임의 구현하지 않고 질문한다.

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

- V1~V8 기존 migration 수정
- 기존 API field rename/remove
- fake provider 성공
- mock/random Recommendation 저장
- InputMethodRecommendation을 WordSuggestion으로 사용
- raw OTP/비밀번호/토큰 로그
- raw webcam frame/눈 이미지/raw landmark/frame별 iris·gaze sequence 저장
- Frontend candidate를 사용자 선택 전에 Backend에 저장하는 흐름
- Calibration에 `inputMethod`별 active 구조를 다시 도입
- `RIDGE`, head-pose, SQPnP, 18차원 feature를 v1 Calibration payload에 추가
- `retest_opt_out`를 현재 UX 근거 없이 추가
- `patient_alias`를 `users.user_name`에 덮어쓰기
- 한 STAFF의 alias를 다른 사용자에게 반환
- Chat 대상 정책 임의 변경
- Email local/profile silent fallback 안전장치 제거

# **24. 권장 작업 단위**

### **Step 1 — 완료**
`V8 + Emergency Entity/Repository/REST`

### **Step 2 — 완료**
`Emergency WebSocket`

### **Step 3 — 완료**
`실제 EmailSender + SMTP/profile fail-safe`

완료 기준선: `./gradlew clean test` 49/49 green.

### **Step 4 — 다음 작업**
`V9 migration`

범위:

```text
calibration
  - user별 공통 active gaze calibration
  - RAW_HOMOGRAPHY JSONB
  - no input_method
input_session_log
  - existing calibration_id에 FK
  - input_method CHECK
  - session_type
user_setting
  - recommended_input_method
  - recommended_at
staff_patient_assignment
  - patient_alias
```

Step 4에서는 API/Recommendation threshold를 구현하지 않는다.

### **Step 5**
`Calibration API`

```text
POST /api/calibrations
GET  /api/calibrations/active
```

### **Step 6**
`InputSession API`

metric 산식/단위/null 계약을 먼저 재확인한다.

### **Step 7**
`InputMethod Recommendation`

추천 정의서 확정 후에만 구현한다.

### **Step 8**
`patient_alias API + STAFF-002 + Chat 표시명`

### **Step 9**
`전체 regression + Swagger + docs`

# **25. 완료 기준**

- V1~V8 기존 migration 무수정
- V9 pristine migration 성공
- Emergency REST 3종 정상
- Emergency WebSocket 정상
- 이메일 실제 SMTP 경로 + production fail-fast 정상
- 사용자별 공통 active gaze Calibration 저장/조회 정상
- Calibration v1 = `RAW_HOMOGRAPHY` + `NORMALIZED_VIEWPORT`
- Calibration에 inputMethod별 별도 Homography 없음
- accepted candidate만 Backend에 저장
- 새로고침/재로그인에서 active Calibration 재사용 가능
- InputSession 저장/조회 정상
- `CALIBRATION_TEST`가 공통 calibration을 참조
- 실제 규칙 기반 InputMethodRecommendation 정상(규칙 확정 시)
- Recommendation이 `currentInputMethod`를 자동 변경하지 않음
- WordSuggestion과 InputMethodRecommendation 분리
- STAFF patient_alias 수정 API 정상
- 의료진별 alias 격리 정상
- 일반 `userName -> loginId` 규칙 유지
- 기존 Chat/E2EE/Emergency/Email 회귀 없음
- 기존 REST API 회귀 없음
- OpenAPI 정상
- 전체 test green
- temporary code 없음
- secret commit 없음

## **부록 — 핵심 흐름**

### **Emergency**

```text
PATIENT
  -> POST /api/emergency-calls
Backend
  -> emergency_call INSERT
DB COMMIT
  -> 담당 STAFF 조회
  -> /user/queue/emergency
```

### **Calibration -> 입력 방식 선택 -> InputSession -> Recommendation**

```text
Browser
  -> 16점 raw gaze calibration
  -> RAW_HOMOGRAPHY Candidate
  -> candidate는 Front memory에만 유지

측정 결과 모달
  ├─ 재측정
  │    -> candidate 폐기
  │    -> 기존 active calibration/currentInputMethod 유지
  │    -> 다시 16점 측정
  │
  └─ EYE_TRACKING / BLINK / MOUTH 선택
       -> POST /api/calibrations
       -> 기존 active revoke + 신규 accepted calibration active
       -> PATCH /api/users/me/settings
          currentInputMethod 변경
       -> 둘 다 성공 시 설정 완료 모달

Frontend metric 계산
  -> POST /api/input-sessions
  -> input_session_log
  -> RecommendationPolicy (규칙 확정 시)
  -> user_setting.recommended_input_method / recommended_at

Analysis
  -> currentInputMethod와 recommendation 표시
  -> 키보드 아이콘으로 언제든 재측정 시작
```

Calibration 자체는 입력 방식을 결정하지 않는다.

```text
Calibration
= iris -> normalized viewport mapping

currentInputMethod
= 실제 confirm 방식

recommendedInputMethod
= 분석 기반 추천 방식
```

### **의료진별 환자 이름**

```text
users.user_name = 김철수

STAFF A assignment.patient_alias = 101호_김철수
STAFF B assignment.patient_alias = NULL

환자 화면 -> 김철수
STAFF A   -> 101호_김철수
STAFF B   -> 김철수
```

## **팀 확인이 필요한 항목**

1. **InputSession metric 계약**: typoRate/inputSpeed/recognitionAccuracy/inputStability의 산식, 단위, null 정책, 측정 시작/종료 시점
2. **InputMethod Recommendation**: 지표별 가중치, threshold, 최소 검사 데이터 수, 동점 처리, 재추천 조건
3. **BLINK confirm 방식**: Python Look-Talk에 완성된 구현이 없으므로 gaze-lock + blink인지, 다른 interaction인지 Front/팀 결정 필요
4. **Chat 권한**: STAFF 담당환자 한정 여부, PATIENT의 STAFF 접근 범위, 배정 해제 후 room/조회/전송 정책

위 항목은 확정 전 Claude가 임의 구현하지 않는다.
