# LookTalk Backend 구현 문서

이 문서는 `docs/LookTalk_Backend_API_Spec_v2.1.md`(API 계약 문서)를 요약한 문서가 아니다. 실제 저장소의 Controller/Service/Repository/Entity/DTO/Security/Migration/WebSocket/E2EE/Test 코드를 직접 확인한 결과를 기준으로, **지금 이 순간 Backend가 실제로 어떻게 구현되어 있는가**를 설명한다. API Spec과 실제 구현이 다른 지점이 있으면 "실제 구현" 기준으로 서술하고 별도 NOTE로 차이를 남긴다.

---

## 1. 구현 개요

LookTalk Backend는 얼굴 신호 기반 AAC(보완대체 의사소통) 시스템 LookTalk의 서버로, 환자(PATIENT)와 의료진(STAFF)의 인증, 개인정보 관리, 메모/자주 쓰는 문장, 지인 관리, 그리고 병원 채팅/SMS 채팅을 담당한다.

현재 실제 코드로 구현되어 있는 범위는 다음과 같다.

- **Auth** — 이메일 인증, 회원가입(환자/의료진), 로그인, Access/Refresh Token, 로그아웃, 비밀번호 재설정
- **User / UserSetting** — 내 정보 조회/수정, 전화번호 인증, 회원탈퇴, 입력 설정
- **Staff** — 의료진 정보, 담당 환자 관리
- **E2EE** — 사용자 공개키 등록/조회
- **Memo** — 암호화된 개인 메모 CRUD
- **Phrase** — 자주 쓰는 문장(최대 3개, FIFO) CRUD
- **Friend** — 외부 SMS 지인 CRUD
- **Chat REST** — 채팅 대상 조회, 채팅방 목록/생성, 메시지 조회/전송 (병원 채팅 + SMS 채팅)
- **WebSocket** — STOMP 기반 실시간 메시지 전달(§58)

실제 컨트롤러의 `@GetMapping/@PostMapping/@PatchMapping/@DeleteMapping` 매핑을 전수 조사한 결과, REST API는 정확히 **41개**다(6절에서 domain별 개수와 함께 다시 표로 정리한다).

**주의**: SMS 발송은 서버 로직/비즈니스 계약은 구현되어 있으나, 실제 SMS Provider(외부 문자 발송 업체)는 아직 연결되어 있지 않다. `USER-003`(전화번호 인증번호 발송)과 `CHAT-006 SMS`가 여기 해당한다. 이 문서에서는 이를 "완료"로 뭉뚱그리지 않고 14절에서 별도로 명확히 구분한다.

---

## 2. 기술 스택

`build.gradle` 기준으로 실제 사용 중인 의존성이다.

| 영역 | 기술 | 비고 |
|---|---|---|
| 언어 | Java 17 | `toolchain languageVersion = 17` |
| 프레임워크 | Spring Boot 4.1.0 | `org.springframework.boot` 플러그인 |
| 빌드 | Gradle | Groovy DSL |
| Web | `spring-boot-starter-webmvc` | REST Controller |
| 영속성 | `spring-boot-starter-data-jpa` | Hibernate, Spring Data JPA Repository |
| DB | PostgreSQL | `org.postgresql:postgresql` (runtime) |
| Migration | Flyway | `spring-boot-starter-flyway` + `flyway-database-postgresql`, `db/migration` 하위 V1~V7 |
| 인증 | `spring-boot-starter-security` | Stateless, `SecurityFilterChain` 직접 구성 |
| JWT | `io.jsonwebtoken:jjwt-api/impl/jackson` 0.12.6 | Access Token 발급/검증에만 사용 |
| Validation | `spring-boot-starter-validation` | Bean Validation(`@Valid`) |
| WebSocket | `spring-boot-starter-websocket` | STOMP 메시지 브로커(`spring-messaging`, `spring-websocket`) |
| API 문서 | `springdoc-openapi-starter-webmvc-ui` 3.0.3 | `/v3/api-docs`, `/swagger-ui.html` |
| E2EE 암호화 | `com.goterl:lazysodium-java` 5.1.4 + `net.java.dev.jna:jna` 5.14.0 | libsodium 네이티브 라이브러리를 JNA로 바인딩. SMS 메시지의 서버측 sealed-box 암호화(`SealedBoxEncryptor`)에 사용한다. HOSPITAL 채팅은 Frontend가 암호화하므로 이 라이브러리를 거치지 않는다 |
| 기타 | Lombok | Entity의 getter/setter/builder 생성(`compileOnly` + `annotationProcessor`) |
| 개발 편의 | `spring-boot-devtools` | 코드 변경 시 애플리케이션 컨텍스트 자동 재기동 |

테스트 의존성은 `*-test` 스타터(`data-jpa-test`, `security-test`, `webmvc-test`, `websocket-test` 등)와 `junit-platform-launcher`를 사용한다. Mockito는 Spring Boot 테스트 스타터에 포함되어 별도 명시 없이 사용 가능하다.

---

## 3. 전체 Backend 구조

### 3.1 패키지 구조

`src/main/java/com/looktalk/backend` 하위는 도메인 패키지로 나뉘어 있고, 각 도메인은 `controller / dto / entity / repository / service`로 구성된다(Friend만 entity가 없다 — `Friendship` Entity를 `user` 패키지가 소유하기 때문).

```
com.looktalk.backend
├ auth            (AUTH-001~009: 인증, 이메일 인증, Refresh Token)
├ user            (USER-001~005, SET-001~002: 사용자/설정, Friendship Entity 포함)
├ staff           (STAFF-001~004: 의료진, 담당 환자)
├ hospital        (Staff가 소속되는 병원 - Entity/Repository만 존재)
├ e2ee            (E2EE-001~003: 공개키, SealedBoxEncryptor)
├ memo            (MEMO-001~004)
├ phrase          (PHRASE-001~004)
├ friend          (FRIEND-001~004: FriendService/Controller. Entity는 user 패키지의 Friendship)
├ chat
│  ├ controller / dto / entity / repository / service   (CHAT-001~006)
│  └ websocket    (§58: WebSocketConfig, WebSocketAuthChannelInterceptor, ChatRealtimePublisher)
└ global
   ├ config       (CorsConfig, HealthController)
   ├ security     (SecurityConfig, JwtTokenProvider, JwtAuthenticationFilter, Sha256Hasher 등)
   ├ exception    (ApiResponse 실패 처리 - ErrorCode, BusinessException, GlobalExceptionHandler)
   ├ response     (ApiResponse<T>)
   └ swagger      (OpenApiConfig, CommonErrorResponseCustomizer)
```

**NOTE**: `calibration / device / emergency / inputsession / patient / recommendation` 패키지와 `global/websocket` 패키지는 디렉터리만 존재하고 `.java` 파일이 0개다(실제 `find` 결과 확인). 프로젝트 초기 스캐폴딩에서 만들어진 빈 디렉터리로, CLAUDE.md가 명시한 "현재 구현 보류" 도메인과 정확히 대응한다. 20절에서 다시 다룬다.

### 3.2 요청 흐름

REST API의 기본 흐름은 다음과 같다.

```
HTTP Request
    ↓
JwtAuthenticationFilter (SecurityFilterChain, UsernamePasswordAuthenticationFilter 앞에 addFilterBefore)
    ↓ Authorization: Bearer {accessToken} 파싱 → SecurityContext에 userId/role 등록
Controller (@RestController)
    ↓
Service (@Transactional 경계, 역할 재검증, 비즈니스 규칙)
    ↓
Repository (Spring Data JPA)
    ↓
PostgreSQL
```

- 인증 실패(401)는 `RestAuthenticationEntryPoint`, 권한 없음(403)은 `RestAccessDeniedHandler`가 처리하며, 둘 다 REST와 동일한 `ApiResponse` JSON 형식으로 응답한다(5절).
- Flyway는 애플리케이션 기동 시 `db/migration`의 `V1~V7`을 순서대로 적용하고, JPA는 `ddl-auto: validate`로 엔티티 매핑이 실제 스키마와 일치하는지만 검증한다(스키마를 자동 생성하지 않는다).
- WebSocket(STOMP)은 REST와 별도의 경로다. `POST /api/chat-rooms/{roomId}/messages`(CHAT-006)가 DB write를 커밋한 **이후에만** `ChatRealtimePublisher`가 `/user/queue/chat`으로 이벤트를 보낸다. WebSocket 자체는 메시지를 생성하지 않는다(15절).

---

## 4. DB 및 Migration

`src/main/resources/db/migration`의 `V1~V7`을 실제로 읽고 정리한 내용이다. **기존에 적용된 migration은 수정하지 않고, 변경은 항상 다음 버전으로 추가한다**는 원칙이 실제로 지켜지고 있다(V7이 V4의 CHECK 제약을 ALTER로 교체하는 방식으로 이를 보여준다).

| Migration | 담당 내용 |
|---|---|
| V1 | `app_health_check` 임시 테이블만 생성(초기 헬스체크용). V6에서 제거됨 |
| V2 | 초기 도메인 스키마: `users`, `user_setting`, `phrase`, `friendship`, `memo`, `chat_room`, `chat_participant`, `message`, `input_session_log` |
| V3 | `hospital`, `staff`, `verification_code` 테이블 추가. `users`에 `login_id`, `role` 컬럼 추가(로그인 ID와 역할 구분 도입) |
| V4 | `login_id`/`role` NOT NULL 전환, `staff_patient_assignment` 테이블 추가, `verification_code.purpose` 추가, Memo/Phrase/Friendship/ChatParticipant에 대한 각종 NOT NULL·CHECK·UNIQUE 인덱스 보강 |
| V5 | `users.user_name`을 NOT NULL에서 nullable로 변경(이름 선택 입력 정책 반영) |
| V6 | `refresh_token`, `user_e2ee_key`, `message_ciphertext` 테이블 신설. `message.content` 컬럼 제거(평문 저장 금지). `memo`/`phrase`에 `key_version`/`encryption_algorithm` 추가. `chat_room.conversation_key` 추가(1:1 room 중복 방지). `verification_code.code` → `code_hash`로 rename(HMAC 해시만 저장) |
| V7 | `chat_participant.is_withdrawn` 컬럼 추가 및 `chk_chat_participant_identity` CHECK 제약 교체(아래 4.1절) |

실제 존재하는 주요 테이블(스키마에 없는 테이블은 기재하지 않는다):

`users`, `user_setting`, `hospital`, `staff`, `staff_patient_assignment`, `verification_code`, `refresh_token`, `user_e2ee_key`, `memo`, `phrase`, `friendship`, `chat_room`, `chat_participant`, `message`, `message_ciphertext`, `input_session_log`

**NOTE**: 사용자가 요청 문서 템플릿에 예시로 든 `social_providers`, `user_stats` 테이블은 실제 migration에 존재하지 않는다. 소셜 로그인/통계 기능은 현재 구현 범위에 없다.

`input_session_log`는 V2에서 테이블만 생성되었고, 이를 다루는 Entity/Repository/Service/Controller는 없다(3.1절의 빈 `inputsession` 패키지). CLAUDE.md 지침대로 테이블은 삭제하지 않고 보류 상태로 남아 있다.

### 4.1 V7 — chat_participant 탈퇴 상태 CHECK 제약 수정

`chat_participant`는 세 종류의 participant를 하나의 테이블에서 표현한다.

| 상태 | `user_id` | `external_phone` | `is_withdrawn` |
|---|---|---|---|
| 정상 내부 회원 | NOT NULL | NULL | FALSE |
| 외부 SMS 지인 | NULL | NOT NULL | FALSE |
| 탈퇴한 내부 회원 | NULL | NULL | TRUE |

V4까지는 `chk_chat_participant_identity`가 `user_id IS NOT NULL OR external_phone IS NOT NULL`만 검사했다. 그런데 `users` DELETE 시 `chat_participant.user_id`는 `ON DELETE SET NULL`(V2)로 정리되도록 설계되어 있었고, 내부 participant는 `external_phone`이 항상 NULL이므로, `user_id`까지 NULL이 되는 순간 이 CHECK를 위반해 **회원탈퇴(USER-005) 자체가 500으로 rollback되는 버그**가 실제 HTTP/DB로 재현되었다.

V7은 `is_withdrawn` 컬럼을 추가하고, 위 표의 세 상태만 허용하도록 CHECK를 교체해 이 문제를 해결한다. **participant 행 자체는 삭제하지 않는다** — `chat_room`/`message` 이력을 보존해, 탈퇴한 회원이 과거에 보낸 메시지도 계속 조회 가능하게 하기 위해서다.

이에 맞춰 `UserService.withdraw()`는 `users` DELETE 전에 반드시 `ChatParticipantRepository.markInternalParticipantsWithdrawn(userId)`를 먼저 호출해 `is_withdrawn=true`와 `user=null`을 **하나의 UPDATE**로 함께 반영한다. CHECK 제약이 즉시(비지연) 평가되므로 두 컬럼을 나눠서 바꾸면 그 사이에 어느 상태에도 속하지 않는 중간 상태가 생겨 위반되기 때문이다.

CHAT-005(`ChatMessageService.toResponse`)는 메시지의 sender participant를 조회할 때 이 세 상태를 다음과 같이 구분한다.

```
sender.getUser() != null              → 정상 내부 회원 (senderUserId = 실제 userId, 이름 = DisplayNameResolver)
sender.getUser() == null && withdrawn → 탈퇴한 내부 회원 (senderUserId = null, 이름 = "탈퇴한 사용자")
sender.getUser() == null && !withdrawn→ 외부 SMS 지인   (senderUserId = null, 이름 = participant 생성 당시 snapshot)
```

CHAT-002(채팅방 목록, `ChatRoomService.buildTarget`)도 상대방 표시에 동일한 3-state 분기를 사용한다.

---

## 5. 공통 API 구조

모든 REST 응답은 `ApiResponse<T>`(`global/response/ApiResponse.java`) record로 감싼다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": { ... }
}
```

실패 시 `success=false`, `code`는 `ErrorCode` enum 이름, `message`는 해당 ErrorCode에 고정된 한국어 문장, `data`는 보통 `null`이다(단 Bean Validation 실패는 `data`에 `ValidationErrorData.fieldErrors` 목록을 담는다).

- **`ErrorCode`**: HTTP Status와 메시지를 가진 enum. `COMMON_*`(공통), `AUTH_*`, `USER_*`, `STAFF_*`, `E2EE_*`, `MEMO_NOT_FOUND`, `PHRASE_NOT_FOUND`, `FRIEND_*`, `CHAT_*`, `SMS_*`로 구성되며, 코드에 실제로 정의된 값만 사용한다(스펙에 없는 코드를 임의로 추가하지 않는다는 CLAUDE.md 원칙이 지켜지고 있다).
- **`BusinessException`**: `ErrorCode`를 담는 `RuntimeException`. Service 계층은 이 예외만 던지고 HTTP 응답 조립은 하지 않는다.
- **`GlobalExceptionHandler`**(`@RestControllerAdvice`): `BusinessException` → 해당 ErrorCode의 status/JSON, `MethodArgumentNotValidException`(`@Valid` 실패) / `HttpMessageNotReadableException`(JSON 파싱 실패) / `MissingServletRequestParameterException`(필수 쿼리 파라미터 누락) → 모두 `COMMON_VALIDATION_ERROR`(400), `NoResourceFoundException`(정의되지 않은 경로) → `COMMON_NOT_FOUND`(404), 그 외 모든 예외 → `COMMON_INTERNAL_ERROR`(500)로 매핑한다.
- **Security 계층 401/403**: `GlobalExceptionHandler`는 `DispatcherServlet` 내부에서만 동작하므로, 그 앞단(Security Filter)에서 발생하는 인증/인가 실패는 별도로 `RestAuthenticationEntryPoint`(401)와 `RestAccessDeniedHandler`(403)가 `SecurityResponseWriter`를 통해 동일한 `ApiResponse` JSON 형식으로 직접 작성한다. 즉 REST 계층뿐 아니라 Security 계층의 오류도 공통 응답 형식을 유지한다.
- **401 세부 코드 구분**: `JwtAuthenticationFilter`가 토큰 파싱에 실패하면 `ExpiredJwtException`은 `COMMON_TOKEN_EXPIRED`, 그 외 `JwtException`/`IllegalArgumentException`은 `COMMON_TOKEN_INVALID`로 request attribute(`looktalk.auth.error`)에 남기고, `RestAuthenticationEntryPoint`가 이를 읽어 세분화된 응답을 만든다. Authorization 헤더 자체가 없으면(attribute 없음) `COMMON_UNAUTHORIZED`를 사용한다.
- **Swagger 공통 에러 스키마**: `CommonErrorResponseCustomizer`가 `/v3/api-docs` 생성 시점에만 개입해, `@Valid @RequestBody`를 쓰는 API의 400 응답에 `ValidationErrorData` 스키마를(이미 업무 규칙 400이 문서화돼 있으면 `oneOf`로 함께) 추가하고, 성공 응답 스키마를 그대로 물려받은 채 방치된 4xx/5xx 응답을 실제 런타임 구조(`ApiResponseVoid`)로 교정한다. 런타임 응답 자체(`ApiResponse`, `GlobalExceptionHandler`)는 건드리지 않는다.

---

## 6. 인증 및 보안

### 6.1 Password

`SecurityConfig`가 `BCryptPasswordEncoder`를 `PasswordEncoder` Bean으로 등록한다. `AuthService.signupPatient/signupStaff`는 `passwordEncoder.encode(...)` 결과만 `users.user_pwd`에 저장하고, `login()`은 `passwordEncoder.matches(...)`로 비교한다. 평문 비밀번호는 어디에도 저장되지 않는다.

### 6.2 Access Token (JWT)

`JwtTokenProvider`가 담당한다.

- `subject` = `userId`(canonical 식별자, loginId/email이 아님), `claim("role")` = `PATIENT`/`STAFF`
- 서명: HMAC(`Keys.hmacShaKeyFor(secret)`), 알고리즘은 `jjwt` 기본값(HS512)
- 만료: `looktalk.jwt.access-token-expiry-seconds`(기본 900초 = 15분)
- 시크릿: `looktalk.jwt.secret` → 환경변수 `JWT_SECRET` (application.yml에 하드코딩되어 있지 않음)
- `JwtAuthenticationFilter`(`OncePerRequestFilter`)가 `Authorization: Bearer {token}`을 파싱해 `parseClaims()`로 검증하고, 성공 시 `UsernamePasswordAuthenticationToken(userId, null, [ROLE_{role}])`을 `SecurityContextHolder`에 등록한다.
- 이 `JwtTokenProvider`는 REST뿐 아니라 WebSocket STOMP CONNECT 인증(`WebSocketAuthChannelInterceptor`)에서도 그대로 재사용된다 — JWT 파서를 두 번 만들지 않는다.

### 6.3 Refresh Token

- **opaque random token**(`OpaqueTokenGenerator`, `SecureRandom` 32바이트 → Base64 URL-safe)이며 JWT가 아니다.
- DB(`refresh_token.token_hash`)에는 원문이 아니라 `Sha256Hasher`로 만든 SHA-256 해시만 저장한다.
- 클라이언트에는 `Set-Cookie: LOOKTALK_REFRESH=...; HttpOnly; Secure; SameSite=Lax; Path=/api/auth`로만 전달된다(Response Body에 노출하지 않음).
- **Rotation**: `AUTH-007`(토큰 재발급)은 기존 Refresh Token을 `revokedAt`으로 폐기하고 새 Refresh Token을 발급해 다시 Cookie로 내려준다(`AuthService.refreshAccessToken`).
- **Revoke**: `AUTH-008`(로그아웃)은 해당 토큰만 폐기하며, 토큰이 없거나 이미 폐기된 상태에서도 멱등적으로 200을 반환한다. `AUTH-009`(비밀번호 재설정) 성공 시에는 `refreshTokenRepository.revokeAllActiveByUserId(...)`로 해당 사용자의 **모든** 활성 Refresh Token을 일괄 폐기한다.

### 6.4 Verification Code (이메일/SMS 인증번호)

- `VerificationCodeService`가 AUTH-001/002와 USER-003/004에서 공유하는 공통 로직이다.
- 6자리 숫자(`SecureRandom.nextInt(1_000_000)`, `%06d`로 zero-padding)
- DB(`verification_code.code_hash`)에는 원문이 아니라 `OtpHasher`(HMAC-SHA256, JWT secret과 별도의 `looktalk.otp.secret` → 환경변수 `OTP_HMAC_SECRET` 사용)의 해시만 저장한다.
- `purpose` 컬럼으로 `SIGNUP`/`PASSWORD_RESET`/`PHONE_VERIFY` 목적을 구분해 같은 email/phone이라도 목적이 다르면 별도 레코드로 취급한다.
- 확인(`confirm`)은 코드 일치 → 만료 시각 순으로 검사하고, 성공 시 `verifiedAt`을 기록한다. 회원가입(AUTH-004/005)은 이 `verifiedAt IS NOT NULL` 레코드가 있는지를 다시 조회해 이메일 인증 완료 여부를 판단한다.

### 6.5 로그 보안 정책 (실제 코드에 적용된 부분)

- `WebSocketAuthChannelInterceptor`는 JWT 파싱 실패 시 원문 토큰이나 malformed 값을 로그에 남기지 않고, `MessagingException` 메시지에 고정 문구만 사용한다.
- `ChatRealtimePublisher.publishMessageCreated`는 publish 실패를 잡을 때 `messageId`/`roomId`/`recipientUserId`만 warning으로 남기고, ciphertext/Payload 전체는 로그에 남기지 않는다(예외 객체는 stacktrace로 남지만 이벤트 payload 자체를 별도로 출력하지 않는다).
- SMS 인증번호/전화번호는 `LoggingSmsSender.sendVerificationCode`가 `"SMS 전화번호 인증번호 발송 요청을 처리했습니다."`라는 고정 문구만 로그로 남기고, 실제 전화번호나 코드 원문은 로그에 남기지 않는다.

---

## 7. Auth 구현 (AUTH-001~009)

| ID | Endpoint | 기능 | 핵심 처리 |
|---|---|---|---|
| AUTH-001 | POST `/api/auth/email-verifications` | 이메일 인증번호 발송 | `purpose=SIGNUP`이면 기존 가입 이메일인지 먼저 확인(`AUTH_DUPLICATE_EMAIL`). `VerificationCodeService.issue()` + `EmailSender`(현재 `LoggingEmailSender`) |
| AUTH-002 | POST `/api/auth/email-verifications/confirm` | 이메일 인증번호 확인 | `purpose=SIGNUP`이면 `{verified:true}`, `purpose=PASSWORD_RESET`이면 사용자를 조회해 opaque reset token을 발급하고 `users.reset_token`(SHA-256 해시)/`reset_token_expiry`에 저장 후 원문 토큰을 응답 |
| AUTH-003 | GET `/api/auth/login-id/availability` | 로그인 아이디 중복 확인 | `!userRepository.existsByLoginId(loginId)` |
| AUTH-004 | POST `/api/auth/patients/signup` | 환자 가입 | loginId/email 중복 검사 + SIGNUP 이메일 인증 완료 검사 → `User(role=PATIENT, userName=null)` 저장 + 기본값(`QWERTY`, `EYE_TRACKING`)의 `UserSetting` 함께 생성 |
| AUTH-005 | POST `/api/auth/staff/signup` | 의료진 가입 | 동일 검증 + 병원 초대코드(`hospital.invite_code`)로 `Hospital` 조회 → `User(role=STAFF)` + `Staff(hospital)` 저장 |
| AUTH-006 | POST `/api/auth/login` | 로그인 | loginId로 사용자 조회 → BCrypt 비교 → Access Token + Refresh Token(Cookie) 발급 |
| AUTH-007 | POST `/api/auth/token/refresh` | Access Token 재발급 | Cookie의 Refresh Token 검증(존재/미폐기/미만료) → 기존 토큰 폐기 + 신규 토큰 발급(Rotation) |
| AUTH-008 | POST `/api/auth/logout` | 로그아웃 | Cookie의 Refresh Token 폐기(멱등) + Cookie 즉시 만료(`maxAge=0`) |
| AUTH-009 | POST `/api/auth/password/reset` | 비밀번호 재설정 | reset token 해시로 사용자 조회 → 만료 확인 → 새 비밀번호 BCrypt 저장, reset token 초기화, 해당 사용자 모든 Refresh Token 폐기 |

이름은 회원가입 시 입력받지 않는다(`userName=null`로 저장) — 이름은 마이페이지(USER-002)에서 선택적으로 설정한다.

의료진(STAFF) 가입은 "초대 코드"(`hospital.invite_code`)로 소속 병원을 특정하는 invite 방식이며, 병원 자체를 생성하는 API는 없다(병원 데이터는 사전에 존재해야 한다).

모든 AUTH-* 엔드포인트는 `PUBLIC`(`SecurityConfig`의 `"/api/auth/**"` permitAll)이다.

---

## 8. User / UserSetting / Staff

### 8.1 displayName 결정 방식

`DisplayNameResolver.resolve(User)`가 유일한 구현 지점이다.

```
user.userName이 존재(공백 아님) → displayName = userName
user.userName이 없음(NULL/공백)  → displayName = loginId
```

`user_id`는 내부 식별자로만 쓰이고 화면 표시용 fallback으로 사용되지 않는다. 이 함수는 User, Staff, ChatContact, ChatRoom target, CHAT-005 sender 표시 등 표시 이름이 필요한 모든 곳에서 재사용된다.

### 8.2 User (USER-001~005, SET-001~002)

- **USER-001** `GET /api/users/me` — `userId/loginId/email/userName/displayName/phone/isEmailVerified/isSmsVerified/role` 반환
- **USER-002** `PATCH /api/users/me` — 이름 설정/수정/삭제(빈 문자열·공백만 있는 값은 trim 후 `null`로 정규화해 "삭제"로 처리)
- **USER-003** `POST /api/users/me/phone-verifications` — PATIENT 전용, `PhoneNormalizer.normalize()`로 숫자만 남긴 뒤 `VerificationCodeService`로 SMS 목적 인증번호 발급. 실제 SMS 발송 상태는 14절 참조
- **USER-004** `POST /api/users/me/phone-verifications/confirm` — 인증 성공 시 `users.user_phone`을 정규화된 번호로 저장하고 `isSmsVerified=true`로 전환
- **USER-005** `DELETE /api/users/me` — 4.1절의 `chat_participant` 탈퇴 처리 후 `users` 삭제. `staff`/`staff_patient_assignment`/`memo`/`phrase`/`friendship`/`user_e2ee_key`/`refresh_token`/`message_ciphertext`는 모두 `users` FK의 `ON DELETE CASCADE`로 DB가 자동 정리한다. **PATIENT/STAFF 모두 담당 관계 존재 여부와 무관하게 탈퇴 가능**하다(STAFF가 담당 환자를 갖고 있어도 차단하지 않는다).
- **SET-001/002** `GET/PATCH /api/users/me/settings` — PATIENT 전용. `keyboardLayout`/`isKeyEnlarged`/`currentInputMethod`(`InputMethod` enum). PATCH는 전달된 필드만 수정하는 partial update다.

USER-003/004/SET-001/002는 URL 레벨(`SecurityConfig.hasRole("PATIENT")`)뿐 아니라 Service 메서드 내부에서도 `requirePatient()`로 역할을 다시 검증한다(Spring Security URL 규칙과 이중 방어).

### 8.3 Staff (STAFF-001~004)

- **STAFF-001** `GET /api/staff/me` — 의료진 본인 정보 + 소속 병원 요약(`hospitalId`/`hospitalName`)
- **STAFF-002** `GET /api/staff/me/patients` — 담당 환자 목록(`staff_patient_assignment` 기준, 배정일 역순)
- **STAFF-003** `POST /api/staff/me/patients` — `loginId`로 PATIENT를 조회해 담당 관계 추가(이미 존재하면 `STAFF_PATIENT_ALREADY_ASSIGNED`)
- **STAFF-004** `DELETE /api/staff/me/patients/{patientUserId}` — 담당 관계만 삭제. **채팅방/메시지는 전혀 건드리지 않는다** — `staff_patient_assignment`는 담당 환자 관리 전용이며 채팅 권한(CHAT-001~006)에는 전혀 사용되지 않는다(코드 어디에서도 CHAT 계열 Service가 이 테이블을 참조하지 않음을 확인).

---

## 9. E2EE 설계

### 9.1 전체 원칙

```
Frontend
    plaintext
    ↓ 상대(또는 본인) 공개키로 sealed box 암호화
    ciphertext (Base64)
    ↓ HTTPS
Backend
    ciphertext만 저장, 복호화하지 않는다
```

Backend는 Memo/Phrase/HOSPITAL 채팅 메시지의 평문을 **한 번도 보지 않는다**. 유일한 예외는 **SMS 채팅**으로, SMS Provider가 평문 문자를 받아야 하므로 `content`(평문)가 Request Body로 Backend에 도달하고, Backend가 Provider 호출 이후 직접 sealed box로 암호화해 저장한다(13.5절, 14절에서 상세히 다룬다). Frontend가 암호화를 끝내고 보내는 HOSPITAL 채팅과는 이 지점에서 완전히 다르다.

### 9.2 User E2EE Key (E2EE-001~003)

`E2eeKeyService` + `user_e2ee_key` 테이블.

- Backend는 **공개키만** 저장한다(`public_key` 컬럼). 개인키는 Request로 받지도, 저장하지도 않는다.
- `key_version`은 클라이언트가 지정하지 않고, 서버가 사용자별로 1부터 순차 증가시켜 계산한다(`findMaxKeyVersion + 1`).
- **key rotation**: 신규 키 등록(E2EE-001) 시 기존 active 키를 전부 `revoked_at`으로 폐기한 뒤 새 키를 저장한다 — 사용자당 활성(active) 키는 항상 최대 1개.
- **동시성**: 사용자 행에 `PESSIMISTIC_WRITE` 락(`findByIdForUpdate`)을 걸어 동시 등록 요청이 같은 버전을 계산하지 않도록 직렬화하고, `(user_id, key_version)` UNIQUE 제약을 최종 방어선으로 둔다.
- **재시도 안정성**: 락 획득 후 현재 active key의 디코딩된 바이트가 Request의 publicKey와 실제로 같으면(Base64 문자열 비교가 아니라 바이트 비교) 새 버전을 만들지 않고 기존 keyVersion을 그대로 반환한다 — 네트워크 재시도로 인한 불필요한 버전 증가를 막는다.
- **공개키 validation**(`validatePublicKeyFormat`): Base64 디코딩 가능 + 디코딩 결과가 정확히 32바이트(libsodium `crypto_box` 공개키 크기)인지 확인. 실패 시 `E2EE_INVALID_PAYLOAD`.
- **E2EE-002**(`GET /api/e2ee/keys/me`): 현재 사용자의 active key 존재 여부와 keyVersion/publicKey 조회.
- **E2EE-003**(`GET /api/e2ee/users/{userId}/public-key`): 대상 사용자의 active key만 조회 가능(다른 사용자의 개인정보는 포함하지 않음). 대상이 아예 존재하지 않으면 `USER_NOT_FOUND`, 존재하지만 active key가 없으면 `E2EE_TARGET_KEY_REQUIRED`.

### 9.3 알고리즘

`EncryptedPayload`(algorithm, keyVersion, ciphertext)와 `EncryptedPayloadValidator`가 Memo/Phrase/HOSPITAL 채팅이 공유하는 공통 계약이다. 현재 허용되는 algorithm은 정확히 하나뿐이다.

```
LIBSODIUM_SEALED_BOX
```

`EncryptedPayloadValidator.validate()`는 algorithm이 이 값과 일치하는지, ciphertext가 유효한 Base64인지만 검사한다(**복호화는 하지 않는다** — Backend는 ciphertext의 내용을 알 수 없다).

### 9.4 Historical key vs 신규 요청의 keyVersion 정책 차이

- **조회(GET, CHAT-005 등)**: 과거에 저장된 ciphertext는 그 시점의 keyVersion을 그대로 반환한다. 이후 키가 폐기(revoked)되었더라도 과거 ciphertext 조회 자체는 막지 않는다 — Frontend가 예전 개인키로 복호화할 수 있어야 하기 때문이다.
- **신규 생성/수정(POST/PATCH, Memo/Phrase, CHAT-006)**: 반드시 **현재 active** keyVersion으로 암호화된 것만 허용한다. `Memo/PhraseService.validateKeyOwnership()`과 `ChatMessageService.sendHospitalMessage()`가 각각 `existsByUser_UserIdAndKeyVersionAndRevokedAtIsNull(...)`(자기 자신) 또는 recipient의 active key(`findActiveKeysByUserIds`)와 keyVersion이 일치하는지 확인하며, 어긋나면 `E2EE_KEY_NOT_FOUND`를 반환한다.

### 9.5 v1 제약

- **multi-device key sync 없음**: 사용자가 여러 기기에서 서로 다른 키 페어를 쓰는 경우를 지원하지 않는다. 한 사용자당 활성 키는 항상 1개.
- **key recovery 없음**: 개인키를 분실하면 과거 ciphertext를 복구할 방법이 없다(Backend가 애초에 개인키를 보관하지 않으므로 구조적으로 불가능).

---

## 10. Memo 구현 (MEMO-001~004)

`MemoService` + `memo` 테이블(`ciphertext`, `key_version`, `encryption_algorithm` 컬럼).

- Backend는 평문 메모를 절대 Request로 받지 않는다 — `MemoRequest`는 `EncryptedPayload`만 받는다.
- 생성/수정 전에 항상 `validateKeyOwnership()`으로 현재 사용자의 active E2EE key 존재 여부와 Request의 keyVersion이 그 active key와 일치하는지 확인한다. 활성 키가 없으면 `E2EE_KEY_REQUIRED`, keyVersion이 맞지 않으면 `E2EE_KEY_NOT_FOUND`.
- `ownership`은 조회/수정/삭제 모두 `memoRepository.findByMemoIdAndUser_UserId(memoId, userId)`로 **소유자 본인 것만** 조회 가능하도록 쿼리 자체에 강제한다(다른 사용자의 memoId를 넣어도 존재 여부가 노출되지 않고 `MEMO_NOT_FOUND`).
- 조회(MEMO-001)는 `updatedAt desc`로 정렬되며, historical ciphertext(과거 keyVersion으로 암호화된 메모)도 그대로 반환한다(9.4절 정책).
- 모든 API는 PATIENT 전용이며, URL 레벨 제약과 별개로 Service에서 `requirePatient()`로 재검증한다.

---

## 11. Phrase 구현 (PHRASE-001~004)

`PhraseService` + `phrase` 테이블. Memo와 거의 동일한 구조이지만 **최대 3개 FIFO 정책**이 핵심이다.

```
registerPhrase(userId, request):
    사용자 행 PESSIMISTIC_WRITE 락 획득 (findByIdForUpdate)
    → E2EE key 소유권/keyVersion 검증
    → count(현재 phrase 개수) >= 3 이면:
          가장 오래된 phrase(createdAt asc 첫 번째) 조회 → DELETE
    → 새 phrase INSERT
    (하나의 @Transactional 안에서 락 획득 → count → delete → insert가 전부 수행됨)
```

- **동시성**: `registerPhrase`는 Memo/Friend/E2EE 키 등록과 동일한 패턴으로 사용자 행에 `PESSIMISTIC_WRITE` 락을 걸어, 동시에 여러 등록 요청이 들어와도 "3개 초과" 판정이 직렬화되도록 한다. 락 없이 count만 확인했다면 두 요청이 동시에 "현재 2개"를 보고 둘 다 등록해 4개가 될 수 있는 race condition을 막는다.
- **삭제 + INSERT는 하나의 Transaction**: `@Transactional` 메서드 안에서 delete와 save가 함께 일어나므로, 중간에 실패하면 오래된 phrase도 삭제되지 않고 새 phrase도 저장되지 않는다(부분 실패로 "3개 미만"이 되는 상태를 만들지 않는다).
- **응답**: `PhraseRegisterResponse`가 새로 만들어진 `phraseId`와 함께, FIFO로 삭제된 경우 `replacedPhraseId`를 함께 반환한다(삭제가 없었으면 `null`).
- **수정(PHRASE-003)**은 `created_at`을 변경하지 않는다(수정해도 FIFO 순서상 "오래된 문장"으로 취급되지 않음) — 이는 스펙 문서에도 명시되어 있고 코드 주석으로도 재확인된다.
- **삭제(PHRASE-004)** API는 사용자의 수동 삭제를 위해 별도로 유지된다(자동 FIFO 삭제와는 별개의 경로).
- CLAUDE.md 지침대로 "채팅 메시지 더블클릭으로 Phrase 등록" 같은 기능은 구현되어 있지 않다 — Phrase는 마이페이지에서만 관리된다.

---

## 12. Friend 구현 (FRIEND-001~004)

`FriendService` + 기존 `friendship` 테이블(V2/V3, Entity는 `user` 패키지 소유). 외부 SMS 지인만 다루며, Chat room/participant/message는 이 단계에서 전혀 건드리지 않는다.

- **PATIENT only**: URL(`SecurityConfig` `/api/friends/**` hasRole PATIENT)과 Service(`requirePatient()`) 양쪽에서 강제.
- **본인 SMS 인증 필요**: 등록/수정 전에 `requireSmsVerified(user)`로 `users.is_sms_verified`를 확인, 미인증이면 `SMS_VERIFICATION_REQUIRED`(403).
- **external friend 구조**: `friend_user_id`는 항상 `null`(친구가 LookTalk 회원인지 여부는 이 API에서 전혀 확인하지 않는다). `friend_name`/`friend_phone`을 등록 시점의 값으로 그대로 저장하는 **snapshot** 방식이다.
- **normalized phone 중복 방지**: `PhoneNormalizer.normalize()`(숫자만 남김)로 정규화한 뒤, 사용자 행 `PESSIMISTIC_WRITE` 락(`findByIdForUpdate`) 하에서 `existsByUser_UserIdAndFriendPhone` 검사 → 이미 존재하면 `FRIEND_ALREADY_EXISTS`. `(user_id, friend_phone)` 부분 UNIQUE 인덱스(V3)를 최종 방어선으로 유지한다.
- **수정(FRIEND-003)**은 전달된 필드만 바꾸는 partial update이며, 소유권은 `friendshipId + currentUserId` 조합으로만 확인해 다른 사용자의 friendship 존재 여부를 노출하지 않는다. 전화번호가 실제로 바뀌는 경우에만(같은 번호로의 "수정"은 제외) 자기 자신을 뺀 중복 검사를 수행한다.
- **Friend 등록 시 채팅방을 미리 생성하지 않는다**: FRIEND-002는 `friendship` insert만 수행하고 `chat_room`/`chat_participant`에는 전혀 손대지 않는다. 실제 SMS room은 CHAT-004(친구를 선택해 채팅을 시작하는 시점)에 find-or-create된다.
- **Friend 삭제(FRIEND-004)가 기존 SMS room에 영향을 주지 않는다**: `deleteFriend()`는 `friendship` 행만 삭제하며, 이미 만들어진 SMS `chat_room`/`chat_participant`/`message`는 전혀 건드리지 않는다 — CHAT-004에서 participant에 저장된 이름/전화번호가 friendship과 독립적인 **snapshot**이기 때문에 가능한 설계다(13.4절).

---

## 13. Chat 전체 설계 (CHAT-001~006)

### 13.1 CHAT-001 — 내부 contact 목록

`GET /api/chat-contacts` → `ChatContactService.getChatContacts`.

- PATIENT/STAFF 모두 대상. **병원 소속/담당 환자 관계(assignment) 제약을 전혀 걸지 않는다** — 현재 모든 회원이 채팅 대상 후보다(CLAUDE.md 정책과 일치).
- `userRepository.searchChatContacts(currentUserId, keyword, pageable)`가 키워드 검색 + 자기 자신 제외를 DB 쿼리 레벨에서 처리하고, `Slice`(count 쿼리 없는 페이지네이션)로 반환한다.
- E2EE ready 여부는 페이지에 포함된 사용자 수만큼 개별 쿼리를 날리지 않고, `userE2eeKeyRepository.findUserIdsWithActiveKey(userIds)`로 활성 키를 가진 userId 집합을 한 번에 조회해 N+1을 피한다.

### 13.2 CHAT-002 — 채팅방 목록

`GET /api/chat-rooms` → `ChatRoomService.getChatRooms`.

- 현재 사용자가 participant인 room만, `type=HOSPITAL|SMS`로 선택적 필터링.
- 각 room의 상대(target) 표시는 4.1절의 3-state(정상 회원/탈퇴 회원/외부 SMS) 분기를 그대로 재사용한다.
- 마지막 메시지는 평문을 만들지 않고 **현재 사용자용 ciphertext**(`EncryptedPayload`)를 그대로 내려준다 — Frontend가 복호화해서 미리보기를 만든다.
- room별 "가장 최근 message"를 방마다 개별 쿼리로 조회하지 않고 `MessageRepository.findLatestPerRoom(roomIds)` 한 번으로 가져오며, 동일 `created_at`을 가진 메시지가 여러 건 나올 수 있는 경우(동시 전송 등) `messageId`가 더 큰 쪽을 최종 1건으로 선택해 동점을 해소한다. ciphertext도 `MessageCiphertextRepository.findAllByMessage_MessageIdInAndRecipientUser_UserId`로 배치 조회한다(N+1 방지).
- 정렬 키는 마지막 메시지가 있으면 그 `createdAt`, 없으면 room `createdAt`(방금 만들어졌지만 메시지가 아직 없는 room도 최신순 정렬에 포함되도록).

### 13.3 CHAT-003 — HOSPITAL room find-or-create

`POST /api/chat-rooms/hospital` → `ChatRoomService.findOrCreateHospitalRoom`.

```
conversation_key = "HOSPITAL:{정렬된 작은 userId}:{정렬된 큰 userId}"
```

두 userId를 문자열 비교(`compareTo`)로 정렬해 순서에 무관하게 항상 동일한 key가 만들어지도록 한다. 검증 순서는 스펙과 동일하게 **대상 없음(`CHAT_TARGET_NOT_FOUND`) → 자기 자신(`CHAT_SELF_TARGET`)** 순서다.

**동시성**: 먼저 `conversation_key`로 기존 room을 조회하고, 없으면 **별도의 `REQUIRES_NEW` Transaction**(`requiresNewTransactionTemplate`)에서 room+participant 생성을 시도한다. 두 요청이 동시에 같은 room을 만들려다 `conversation_key` UNIQUE 제약(V6)을 위반하면(`DataIntegrityViolationException`), 그 실패를 호출 측 Transaction으로 전파시키지 않고 완전히 분리된 새 Transaction에서 다시 조회해 경쟁에서 이긴 요청이 만든 room을 반환한다. `REQUIRES_NEW`를 쓰지 않았다면 실패한 INSERT가 바깥 Transaction 전체를 rollback-only로 오염시켰을 것이다.

### 13.4 CHAT-004 — SMS room find-or-create

`POST /api/chat-rooms/sms` → `ChatRoomService.findOrCreateSmsRoom`. **PATIENT 전용**.

```
conversation_key = "SMS:{currentUserId}:{normalizedFriendPhone}"
```

Request의 원본 전화번호가 아니라 **DB에 저장된(이미 정규화된) `friendship.friend_phone`을 source of truth**로 사용한다. 사전 조건: PATIENT + `isSmsVerified` + 본인 active E2EE key 존재(`E2EE_KEY_REQUIRED`). room 생성 시 `friendship.friend_name`/`friend_phone`을 `chat_participant.external_name`/`external_phone`에 **snapshot으로 복사**해 저장한다 — 이후 friendship이 수정/삭제되어도 이미 만들어진 room의 상대 표시가 영향받지 않게 하기 위해서다(12절).

동시성 처리는 CHAT-003과 동일한 find-or-create + `REQUIRES_NEW` 패턴을 공유한다.

### 13.5 CHAT-005 — 메시지 조회

`GET /api/chat-rooms/{roomId}/messages` → `ChatMessageService.getMessages`.

- room 존재 확인 후, participant가 아니면 `CHAT_ROOM_NOT_FOUND`로 존재를 숨기지 않고 **`CHAT_ACCESS_DENIED`**를 명시적으로 반환한다.
- **2단계 조회**로 N+1을 방지한다.
  1. `MessageRepository.findMessageIdsForCurrentUser(roomId, currentUserId, beforeMessageId, Pageable(size+1))` — **"현재 사용자용 ciphertext가 존재하는 message"만**을 `EXISTS` 서브쿼리로 필터링하며 `messageId DESC` cursor pagination. `size+1`건을 가져와 그 크기로 `hasNext`를 판단하므로 **별도 COUNT 쿼리가 없다**.
  2. 1단계에서 확정된 messageId 목록에 대해 `findAllByIdInWithSender`(message + sender participant + sender user를 `join fetch`/`left join fetch`로 한 번에)와 `findAllByMessage_MessageIdInAndRecipientUser_UserId`(현재 사용자용 ciphertext만)를 각각 배치 조회한다. IN 절 결과는 순서를 보장하지 않으므로, Service가 1단계가 정한 `messageId DESC` 순서로 다시 정렬해 최종 응답을 만든다.
- **ciphertext isolation**: 2단계 쿼리 자체가 `recipient_user_id = 현재 사용자`로 매칭되므로, 다른 recipient의 ciphertext가 섞일 방법이 구조적으로 없다.
- **historical keyVersion**: 과거에 폐기된 keyVersion으로 암호화된 메시지도 조회 시점의 keyVersion 그대로 반환한다(9.4절).
- **sender 3-state**: 4.1절에서 설명한 정상/탈퇴/외부 3분기를 그대로 사용한다. `toResponse()`는 1단계가 이미 "ciphertext 존재"를 보장했음에도 message 또는 ciphertext가 `null`이면(데이터 정합성 문제) 다른 recipient의 ciphertext로 대체하거나 평문 fallback을 만드는 대신 **즉시 `IllegalStateException`**을 던져 문제를 숨기지 않는다.

### 13.6 CHAT-006 — HOSPITAL 메시지 전송

`POST /api/chat-rooms/{roomId}/messages` → `ChatMessageService.sendMessage` → (`room.roomType == HOSPITAL`이면) `sendHospitalMessage`.

```
REST 요청 (ciphertexts[recipientUserId, keyVersion, algorithm, ciphertext])
    ↓
messageType 검증(TEXT만 허용) + room 조회 + 현재 사용자가 participant인지 확인(CHAT_ACCESS_DENIED)
    ↓
room의 활성(비탈퇴) 내부 participant 전원 조회 + 각자의 active E2EE key 조회
    ↓
검증: 본인 active key 존재(E2EE_KEY_REQUIRED)
      → 탈퇴한 참여자가 있으면 즉시 CHAT_TARGET_NOT_FOUND(신규 전송 불가, 과거 이력 조회는 계속 가능)
      → 활성 참여자 중 active key 없는 사람 있으면 E2EE_TARGET_KEY_REQUIRED
      → Request recipient 집합 == 활성 참여자 userId 집합 정확히 일치(다르면 E2EE_INVALID_PAYLOAD, 중복 recipient도 거부)
      → 각 ciphertext의 algorithm/형식(EncryptedPayloadValidator) + keyVersion이 그 recipient의 "현재 active" 버전과 일치(E2EE_KEY_NOT_FOUND)
    ↓
TransactionTemplate.execute { Message 저장 + recipient별 MessageCiphertext 저장 } ← 여기서 commit
    ↓ (execute()가 정상 반환 = 이미 commit 완료)
활성 participant 전원(sender 본인 포함)에게 recipient별로 분리된 WebSocket Event publish
```

**sender ciphertext도 포함하는 이유**: HOSPITAL은 recipient 집합이 "활성 내부 participant 전원"이며 sender 자신도 그 집합의 일원이다. Frontend가 자기 자신이 방금 보낸 메시지도 나중에 복호화해서 다시 보여줘야 하므로(자기 화면에도 대화가 남아야 함), sender 본인용 ciphertext(자신의 공개키로 암호화된 사본)도 Request에 포함되어야 하고, 이를 그대로 저장/발행한다.

DB write(Transaction)와 WebSocket publish가 분리되어 있는 이유, offline/실패 처리는 15절에서 자세히 다룬다.

### 13.7 CHAT-006 — SMS 메시지 전송

같은 endpoint, `room.roomType == SMS`일 때 `sendSmsMessage`가 처리한다. HOSPITAL과는 흐름이 근본적으로 다르다.

```
REST 요청 (content: 평문)
    ↓
PATIENT 전용 + isSmsVerified 확인
    ↓
현재 사용자의 active E2EE key 조회(E2EE_KEY_REQUIRED)
    ↓
room의 외부 SMS participant 조회(CHAT-004 생성 당시 snapshot의 external_phone 사용)
    ↓
smsMessageSender.isConfigured()?  → false면 SMS_PROVIDER_NOT_CONFIGURED(503)로 즉시 종료, DB write 없음
    ↓ true
smsMessageSender.send(phone, content)  ← DB write Transaction 밖에서 호출
    → 실패(SmsSendFailedException) 시 SMS_SEND_FAILED(502)로 종료, DB write 없음
    ↓ Provider 수락
Backend가 SealedBoxEncryptor로 content를 "현재 사용자 자신의" active 공개키로 암호화
    ↓
TransactionTemplate.execute { Message 저장 + PATIENT 본인용 MessageCiphertext 1건만 저장 } ← commit
    ↓
현재 PATIENT(currentUserId)에게만 WebSocket Event publish
```

Frontend가 sealed box 암호화를 마쳐서 보내는 HOSPITAL과 달리, SMS는 **평문이 Request Body로 Backend에 도달**한다(SMS Provider가 평문 문자를 받아야 하므로 구조적으로 불가피). Backend는 이 평문을 DB나 로그에 저장하지 않고, Provider 호출 완료 직후 곧바로 `SealedBoxEncryptor.encrypt()`로 암호화해서만 저장한다. 저장되는 ciphertext는 **환자 본인만** 복호화 가능한 사본 1건뿐이다 — 외부 SMS 상대는 LookTalk 사용자가 아니므로 `message_ciphertext`에 그 상대용 사본이 존재하지 않는다.

**Provider 호출과 DB Transaction의 분리**: `sendMessage()` 메서드 자체는 `@Transactional`이 아니다(private 메서드 호출까지 걸리면 Provider 호출까지 Transaction에 포함되기 때문). 저장 단계만 `TransactionTemplate`로 명시적으로 감싼다 — Provider 네트워크 호출이 DB 커넥션/Transaction을 오래 붙잡지 않도록 하기 위해서다. HOSPITAL 쪽도 일관성을 위해 동일한 `TransactionTemplate` 패턴을 사용한다.

**현재 실제 상태**: `UnconfiguredSmsMessageSender`(`isConfigured() == false` 고정)가 유일한 `SmsMessageSender` 구현체이므로, 이 흐름의 서버 로직 자체는 완결돼 있지만 **실제 SMS 발송은 항상 503으로 막힌다**. 14절 참조.

---

## 14. SMS 관련 현재 상태

이 절은 혼동을 막기 위해 세 기능을 명확히 분리한다. 서버 비즈니스 로직 구현 여부와, 실제로 문자가 외부로 발송되는지는 **별개**다.

### 14.1 USER-003 전화번호 인증번호 발송

- **API/비즈니스 로직**: 구현 완료(`UserController.sendPhoneVerification` → `UserService.sendPhoneVerification` → `VerificationCodeService.issue` + `SmsSender.sendVerificationCode`).
- **실제 SMS**: **미연동**.
- **현재 구현체**: `user.service.LoggingSmsSender` — `SmsSender` 인터페이스의 유일한 구현체이며, 실제 발송 대신 `log.info("SMS 전화번호 인증번호 발송 요청을 처리했습니다.")`만 남긴다. 전화번호/인증번호 원문은 로그에 남기지 않는다. 인증번호를 실제로 확인하려면(로컬 개발 시) DB의 `verification_code`를 직접 조회해야 한다(코드는 해시로 저장되므로 DB 조회로도 원문은 알 수 없고, 개발 시엔 별도 우회가 필요하다).
- 이 `SmsSender`(user 패키지, `sendVerificationCode` 전용)는 아래 CHAT-006의 `SmsMessageSender`(chat 패키지, 임의 문자열 발송)와 **서로 다른 인터페이스**다 — 인증번호 발송이라는 목적과 채팅 메시지 발송이라는 목적이 다르므로 억지로 하나로 묶지 않았다.

### 14.2 CHAT-006 SMS outbound

- **서버 비즈니스 로직**: 구현 완료(13.7절).
- **실제 Provider**: **미연동**.
- **현재 구현체**: `chat.service.UnconfiguredSmsMessageSender` — `isConfigured()`가 항상 `false`를 반환하는, 모든 프로필(local 포함)에서 정직하게 "미설정" 상태를 유지하는 구현체다. "로컬이니까 무조건 성공"하는 fake Provider를 만들지 않았다.
- **동작**: Provider가 설정되지 않은 상태에서 SMS room에 메시지를 보내면 즉시 `SMS_PROVIDER_NOT_CONFIGURED`(503)를 반환하고 어떤 DB write도 발생하지 않는다.
- Provider가 실제로 선정되면 `SmsMessageSender` 인터페이스(`isConfigured()`, `send(phone, content)`)를 만족하는 구현체로 교체하기만 하면 되도록 계약이 이미 분리되어 있다.

### 14.3 §59 SMS inbound webhook

- **미구현**. 외부 SMS 상대가 보낸 답장을 받아 LookTalk 채팅으로 반영하는 기능이다.
- Provider 선정 후 별도로 구현 예정: Provider별 webhook DTO, signature 검증, 수신 전화번호로 기존 SMS room 매칭, Backend의 서버측 sealed-box 암호화(환자 본인 공개키로), DB 저장, WebSocket 이벤트 발행.
- 이번 작업에서는 §58 WebSocket까지만 구현했고, 사용자의 명시적 지시에 따라 §59로 진행하지 않았다.

**요약**: SMS는 "완료"가 아니라 **"서버 로직 구현 완료 / 외부 Provider 연동 미완료"**로 표현하는 것이 정확하다.

---

## 15. WebSocket 구현 (§58)

### 15.1 REST → WebSocket 흐름

```
POST /api/chat-rooms/{roomId}/messages (CHAT-006)
    ↓
TransactionTemplate.execute { Message + MessageCiphertext 저장 }   ← commit 완료
    ↓ (execute() 반환 이후에만, 콜백 내부가 아님)
ChatRealtimePublisher.publishMessageCreated(recipientUserId, event)
    ↓
SimpMessagingTemplate.convertAndSendToUser(recipientUserId, "/queue/chat", event)
    ↓
/user/queue/chat 구독 중인 recipient 세션으로 전달
```

REST(CHAT-006)가 메시지 생성의 **유일한 Source of Truth**이며, WebSocket은 배달 전용(delivery-only)이다. `@MessageMapping` 기반 endpoint는 존재하지 않는다 — client가 STOMP SEND로 메시지를 만들 수 없다.

### 15.2 Endpoint / Protocol

- Endpoint: `/ws` (`WebSocketConfig.registerStompEndpoints`)
- Protocol: STOMP over 순수 WebSocket. **SockJS는 사용하지 않는다**.
- Origin 정책: 별도 하드코딩 값이나 `*` 없이, REST CORS와 **동일한** `looktalk.cors.allowed-origins` 설정값을 그대로 재사용한다(`WebSocketConfig` 생성자에 `@Value`로 주입).

### 15.3 인증

- **HTTP handshake**(`GET /ws` upgrade)는 `SecurityConfig`에서 `permitAll`이다. **이것이 "인증 없이 WebSocket 사용 가능"을 의미하지 않는다** — 실질적인 인증 경계는 STOMP CONNECT 단계다.
- JWT는 **STOMP CONNECT 프레임의 native header** `Authorization: Bearer {accessToken}`으로만 전달한다. URL 쿼리 파라미터(`/ws?token=...`)나 Refresh Token/Cookie는 사용하지 않는다(로그/프록시에 토큰이 노출되는 위험을 피하기 위해).
- `WebSocketAuthChannelInterceptor`(`ChannelInterceptor.preSend`)가 CONNECT 프레임에서 `JwtTokenProvider.parseClaims()`(REST와 동일한 파서)로 검증하고, `subject`(userId)와 `role` claim으로 `UsernamePasswordAuthenticationToken`을 만들어 `accessor.setUser(...)`로 STOMP 세션에 등록한다. JWT가 없거나 유효하지 않으면 `MessagingException`을 던져 CONNECTED 프레임을 받지 못하게 한다(연결 자체가 성립하지 않음).
- **Principal name = canonical userId**다. loginId/email/displayName은 라우팅 키로 쓰지 않는다.
- 구현 중 발견된 회귀: `StompHeaderAccessor.wrap(message)`로 새 accessor를 만들어 `setUser()`를 호출하면 그 변경이 세션에 반영되지 않아, CONNECT에서 인증에 성공해도 이후 SUBSCRIBE 프레임에서 `accessor.getUser()`가 `null`로 관측되는 문제가 있었다. `MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)`로 디코더가 이미 붙여둔 mutable accessor를 그대로 사용하도록 수정했고, 이 계약은 `ChatWebSocketIntegrationTest`(17절)로 회귀 테스트가 남아 있다.

### 15.4 SUBSCRIBE / SEND 제약

- **허용**: `/user/queue/chat` 단 하나.
- **거부**: `/topic/**`, raw `/queue/**`(user prefix 없이), 다른 사용자의 `/user/{other}/queue/**`, 그리고 client의 STOMP SEND 프레임 전체. 모두 `requireAuthenticatedSubscription()` 또는 `preSend()`의 SEND 분기에서 `MessagingException`으로 거부되며, 서버가 연결을 종료한다.
- room 단위 `/topic` broadcast는 쓰지 않는다 — 사용자마다 ciphertext가 다르므로 애초에 방 전체에 같은 payload를 broadcast할 수 없는 구조다.

### 15.5 Event

```json
{
  "type": "MESSAGE_CREATED",
  "roomId": 10,
  "data": {
    "messageId": 299,
    "senderParticipantId": 4,
    "senderUserId": "U001",
    "senderDisplayName": "김철수",
    "encryptedPayload": { "algorithm": "LIBSODIUM_SEALED_BOX", "keyVersion": 1, "ciphertext": "..." },
    "createdAt": "2026-08-08T18:50:36Z"
  }
}
```

`ChatWebSocketEvent`(type/roomId/data)와 `ChatMessageCreatedEventData`가 이 구조를 그대로 구현한다. CHAT-005/CHAT-006 응답에 있는 `messageType` 같은 필드는 이 Event에는 없다 — 스펙에 정의된 필드만 정확히 담는다.

### 15.6 E2EE isolation

HOSPITAL 전송은 활성 내부 participant 각각에게 **별도의 Event**를 발행하며, 각 Event의 `encryptedPayload`는 그 recipient용 ciphertext만 담는다(13.6절 for문 참고). A/B 두 사용자가 있다면 A는 A용 ciphertext가 담긴 Event 1건, B는 B용 ciphertext가 담긴 Event 1건을 각각 받으며, 서로의 ciphertext를 볼 수 없다. `ChatWebSocketIntegrationTest.hospitalSendDeliversIsolatedCiphertextOnlyToRoomParticipants`가 이를 실제 STOMP 프로토콜로 검증한다.

### 15.7 offline / 전달 실패 정책

- WebSocket은 **best-effort** 실시간 전달 채널이다. 영속적/재시도 가능한 메시지 채널이 아니다.
- recipient가 offline이어도(WebSocket 세션이 없어도) CHAT-006 REST 요청과 DB 커밋은 정상적으로 성공한다 — WebSocket 전달 가능 여부가 REST 성공/실패에 전혀 영향을 주지 않는다.
- 재접속한 클라이언트는 CHAT-005(메시지 조회 REST)로 놓친 이력을 복구한다. WebSocket 자체에 재전송/큐잉 메커니즘은 없다.
- **publisher failure isolation**: `ChatRealtimePublisher.publishMessageCreated()`가 `SimpMessagingTemplate.convertAndSendToUser()` 호출을 자체적으로 try-catch로 감싸 모든 `RuntimeException`을 흡수한다. publish가 실패해도 이미 commit된 REST 응답이나 DB 상태는 전혀 영향받지 않는다 — `ChatMessageService`는 publish 결과를 확인하지 않고 호출만 한다. 이 계약은 `ChatRealtimePublisherTest`가 throwing mock `SimpMessagingTemplate`으로 검증한다(17절).

### 15.8 적용 범위(한계)

현재는 단일 서버 인스턴스의 Spring **simple broker**(`enableSimpleBroker`)만 사용한다. Redis/RabbitMQ 등 broker relay를 통한 다중 인스턴스(수평 확장) 간 메시지 전달은 이번 단계 범위 밖이며 구현되어 있지 않다 — 서버를 여러 대로 띄우면, 서로 다른 인스턴스에 연결된 클라이언트 간에는 실시간 전달이 보장되지 않는다.

---

## 16. Transaction / Concurrency

여러 도메인에 걸쳐 반복되는 Transaction/동시성 패턴을 모아 정리한다.

| 기능 | 패턴 | 목적 |
|---|---|---|
| Refresh Token rotation(AUTH-007) | 단일 `@Transactional` | 기존 토큰 revoke + 신규 토큰 저장을 원자적으로 |
| 비밀번호 재설정(AUTH-009) | 단일 `@Transactional` | 비밀번호 변경 + 모든 Refresh Token 폐기를 원자적으로 |
| Phrase FIFO(PHRASE-002) | `findByIdForUpdate`(사용자 행 `PESSIMISTIC_WRITE`) + 단일 `@Transactional` | 동시 등록 시 3개 초과 방지, 삭제+INSERT를 하나의 커밋으로 |
| Friend 중복 방지(FRIEND-002/003) | `findByIdForUpdate` + 단일 `@Transactional` | 동시 등록으로 같은 전화번호가 중복 저장되는 것 방지 |
| E2EE key 등록(E2EE-001) | `findByIdForUpdate` + 단일 `@Transactional` | 동시 등록 요청이 같은 keyVersion을 계산하지 않도록 직렬화 |
| HOSPITAL/SMS room find-or-create(CHAT-003/004) | 조회 → 없으면 `REQUIRES_NEW` 별도 Transaction으로 생성 시도 → UNIQUE 위반 시 새 Transaction으로 재조회 | 방 생성 경쟁 실패가 호출 측 Transaction 전체를 rollback-only로 오염시키지 않도록 격리 |
| HOSPITAL message 저장(CHAT-006) | 검증(Transaction 밖) → `TransactionTemplate`로 저장만 감쌈 → commit 이후 WebSocket publish | 검증 실패 시 아무 write도 남기지 않고, publish는 반드시 commit 확정 후에만 |
| SMS message 저장(CHAT-006) | Provider 호출(Transaction 밖) → 성공 시에만 암호화+`TransactionTemplate`로 저장 → commit 이후 publish | Provider 네트워크 호출이 DB Transaction/커넥션을 오래 붙잡지 않도록 분리 |
| USER-005 회원탈퇴 | `markInternalParticipantsWithdrawn`(단일 UPDATE) → `users` DELETE, 같은 `@Transactional` 안 | `chk_chat_participant_identity`(V7) CHECK가 즉시 평가되므로 중간 상태 없이 한 번에 전이 |

공통적으로: 사용자 단위로 경쟁이 발생할 수 있는 곳(Phrase/Friend/E2EE key)은 **비관적 락 + 단일 Transaction**, room 생성처럼 실패가 정상적인 경로인 곳(CHAT-003/004)은 **REQUIRES_NEW로 격리 후 재조회**, 외부 I/O(SMS Provider, WebSocket publish)가 끼는 곳은 **DB Transaction 경계를 명시적으로 분리**하는 세 가지 전략이 일관되게 적용되어 있다.

---

## 17. 테스트 및 검증

`src/test/java` 하위에 실제로 존재하는 자동화 테스트는 다음 4개 클래스다(그 외 클래스는 없다).

| 클래스 | 위치 | 검증 내용 |
|---|---|---|
| `LooktalkBackendApplicationTests` | `com.looktalk.backend` | `@SpringBootTest` context load(Flyway V1~V7 migration + Hibernate `ddl-auto=validate` 통과 포함) |
| `SealedBoxEncryptorTest` | `e2ee.service` | `SealedBoxEncryptor.encrypt()`가 실제 libsodium `crypto_box_seal`과 **호환**되는지 — 실제 공개키/개인키 쌍으로 암호화 → 복호화 왕복시켜 한국어 평문이 완전히 동일하게 복원되는지 확인 |
| `ChatRealtimePublisherTest` | `chat.websocket` | Mockito로 `SimpMessagingTemplate`을 mock. (1) 정상 호출 시 `convertAndSendToUser(userId, "/queue/chat", event)`가 정확히 호출되는지, (2) mock이 `MessagingException`을 던지도록 설정했을 때 `publishMessageCreated()` 호출이 예외를 전파하지 않고 흡수하는지(publish 실패 격리, 15.7절) |
| `ChatWebSocketIntegrationTest` | `chat.websocket` | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + 실제 STOMP 프로토콜 클라이언트(`WebSocketStompClient`). ① 유효 JWT로 CONNECT 후 `/user/queue/chat` SUBSCRIBE까지 세션이 유지되는지(15.3절 회귀 재발 방지), ② Authorization 헤더 없이 CONNECT 시 실패, ③ 유효하지 않은 JWT로 CONNECT 시 실패, ④ HOSPITAL room에서 A가 CHAT-006으로 전송 시 A/B가 각각 자신의 ciphertext만 담긴 Event를 받고 room 비참여자는 Event를 받지 않는지(15.6절) — 테스트 내에서 JPA Repository로 직접 사용자/room/E2EE key를 만들고 `@AfterEach`에서 정리한다 |

**구현 과정 수동 검증**(자동화 테스트가 아니므로 여기서 명확히 구분한다): 위 4개 클래스 외에도 구현 단계마다 임시 테스트 클라이언트/컨트롤러(예: 실제 서버를 띄워 curl/STOMP client로 CHAT-005/006 HOSPITAL·SMS E2E, USER-005 탈퇴 후 CHAT-005 조회, SecurityConfig의 401/403, `/ws` handshake 101 응답 등)를 만들어 수동으로 검증했으나, 검증이 끝난 뒤 모두 삭제했다. 이 문서와 이전 대화 기록에 등장하는 "실제 HTTP/DB로 재현됨" 같은 서술은 이 수동 검증을 가리키며, 현재 저장소의 자동화 테스트 스위트에는 포함되어 있지 않다.

---

## 18. Swagger / API 문서

- `springdoc-openapi-starter-webmvc-ui`가 `/v3/api-docs`(원본 JSON)와 `/swagger-ui.html`(→ `/swagger-ui/index.html`)을 자동 생성한다. 둘 다 `SecurityConfig`에서 `permitAll`이다.
- `OpenApiConfig`가 `bearerAuth`(HTTP Bearer, JWT) SecurityScheme 하나만 공통 등록한다. 전역 SecurityRequirement는 적용하지 않고, 인증이 필요한 Controller/메서드마다 개별적으로 `@SecurityRequirement(name = "bearerAuth")`를 붙인다.
- `CommonErrorResponseCustomizer`가 5절에서 설명한 공통 에러 스키마 보정을 수행해, 실제 런타임 오류 응답 구조(`ApiResponse`, `success=false`)와 Swagger 문서상의 스키마가 어긋나지 않도록 한다.
- **`docs/LookTalk_Backend_API_Spec_v2.1.md`(API Spec)와 이 문서(Implementation)의 차이**: API Spec은 Frontend와 맺는 **계약 문서**로, Endpoint/Request/Response/ErrorCode를 정의하고 "무엇을 지켜야 하는가"를 규정한다(CLAUDE.md가 이를 Source of Truth로 지정). 이 문서는 그 계약이 **실제로 어떻게 구현되어 있는가**(내부 구조, 알고리즘, Transaction 경계, 알려진 제약)를 코드 기준으로 설명하는 별도 문서다. Swagger(`/v3/api-docs`)는 이 둘 중 API Spec에 대응하는 자동 생성 산출물이다.

---

## 19. 현재 구현 완료 현황

실제 컨트롤러 `@GetMapping/@PostMapping/@PatchMapping/@DeleteMapping` 매핑을 전수 조사(6개 컨트롤러 파일)한 결과다.

| Domain | API 개수 | 상태 |
|---|---:|---|
| Auth | 9 | 완료 |
| User | 5 | 완료 |
| UserSetting | 2 | 완료 |
| Staff | 4 | 완료 |
| E2EE | 3 | 완료 |
| Memo | 4 | 완료 |
| Phrase | 4 | 완료 |
| Friend | 4 | 완료 |
| Chat | 6 | 완료 |
| **REST 합계** | **41** | |
| WebSocket | §58 | 완료(15절) |

**주석(반드시 함께 읽을 것)**:
- **USER-003**: API/로직 완료. 실제 SMS Provider 미연동(`LoggingSmsSender`, 14.1절).
- **CHAT-006 SMS**: API/로직 완료. 실제 SMS Provider 미연동(`UnconfiguredSmsMessageSender`, 14.2절 — 항상 `SMS_PROVIDER_NOT_CONFIGURED` 503).
- CHAT-006 HOSPITAL은 Provider 의존이 없으므로 이 주석이 해당하지 않는다 — 완전히 동작한다.

---

## 20. 미구현 / 후속 작업

### A. 외부 Provider 연동 후 구현

- 실제 SMS Provider 선정
- USER-003의 실제 문자 발송(`LoggingSmsSender` → 실제 구현체 교체)
- CHAT-006 SMS의 실제 outbound 발송(`UnconfiguredSmsMessageSender` → 실제 구현체 교체)
- §59 SMS inbound webhook (14.3절)
- webhook signature 검증

### B. 설계 보류

- **InputSession**: `input_session_log` 테이블은 V2에서 이미 존재하지만, 이를 다루는 Entity/Repository/Service/Controller는 전혀 없다(`inputsession` 패키지가 비어 있음, 3.1절). 현재 API 계약이 이 테이블을 사용하지 않는다. 요구사항이 확정된 뒤 별도로 다시 설계할 예정(CLAUDE.md 지침).

### C. v1 범위에서 의도적으로 제외

아래는 구현 누락이 아니라 **v1 scope에서 의도적으로 제외**된 항목이다(CLAUDE.md "현재 구현 보류" 목록과 실제 빈 패키지 확인 결과가 일치한다).

- Calibration (`calibration` 패키지 비어 있음)
- Recommendation (`recommendation` 패키지 비어 있음)
- Emergency (`emergency` 패키지 비어 있음)
- Device / Jetson Nano (`device` 패키지 비어 있음)
- Patient 전용 별도 도메인 패키지(`patient` 패키지 비어 있음 — 현재 "환자"는 `users.role = PATIENT`로만 구분되며 별도 Patient 테이블/Entity가 없다. `staff_patient_assignment.patient_user_id`가 `users`를 직접 참조한다)
- 관리자(Admin) 기능 — 관리자 전용 API/역할이 코드 어디에도 없다
- E2EE multi-device key synchronization / key recovery (9.5절)
- SMS delivery status(발송 성공/실패 상태 추적) — `message`/`message_ciphertext` 스키마에 관련 컬럼 없음(V4 주석에 "SMS 업체 선정 후 필요 시 추가"로 명시)

---

## 21. 보안상 중요한 설계 결정

| 결정 | 이유 |
|---|---|
| 비밀번호는 BCrypt 해시만 저장 | 평문/역가능한 형태로 저장하지 않아 DB 유출 시에도 원문 복구를 어렵게 함 |
| Refresh Token / Password Reset Token은 SHA-256 해시만 DB에 저장, 원문은 Cookie/응답으로만 1회 전달 | DB 유출 시에도 해시만으로는 토큰을 재사용할 수 없음 |
| OTP(이메일/SMS 인증번호)는 원문 대신 HMAC-SHA256 해시만 저장 | 인증번호 원문이 DB나 백업에 남지 않도록 함 |
| E2EE 개인키를 Backend가 절대 저장/수신하지 않음 | 서버가 침해되어도 채팅/메모 내용을 복호화할 수 없는 구조적 보장(zero-knowledge에 가까운 설계) |
| Memo/Phrase/HOSPITAL 채팅 평문을 Backend가 저장하지 않음 | 개인 메모와 병원 채팅 내용은 서버 운영자도 볼 수 없어야 함 |
| SMS 채팅 평문은 Provider 호출 직후 즉시 암호화하고 DB/로그에 남기지 않음 | Provider 특성상 일시적으로 평문을 다뤄야 하는 유일한 예외를 최소 범위로 제한 |
| `message_ciphertext`가 recipient별로 분리되어 사용자별 ciphertext isolation을 DB 스키마 레벨에서 강제 | 조회 쿼리가 `recipient_user_id`로 매칭되므로 다른 사용자의 ciphertext가 섞일 방법이 구조적으로 없음 |
| WebSocket 인증에 Access Token을 URL 쿼리 파라미터로 쓰지 않음(STOMP CONNECT native header만 사용) | 쿼리 파라미터는 서버 접근 로그, 프록시 로그, 브라우저 히스토리 등에 노출되기 쉬움 |
| REST(CHAT-006)만 메시지 생성의 Source of Truth, WebSocket은 배달 전용 | 인증/검증/트랜잭션 경계가 한 곳(REST)에만 있어 일관성을 보장하기 쉬움. STOMP SEND를 열면 그 경로에도 동일한 검증을 중복 구현해야 함 |
| WebSocket publish는 DB commit 이후에만 수행(commit 콜백 내부가 아님) | 아직 commit되지 않은(혹은 이후 rollback될 수 있는) 메시지에 대해 실시간 이벤트가 먼저 나가는 상황을 원천 차단 |
| WebSocket publish 실패를 REST 성공/DB 상태와 완전히 분리 | best-effort 채널의 실패가 이미 완료된 핵심 트랜잭션(메시지 저장)을 되돌리는 것은 잘못된 실패 모델이기 때문 |
