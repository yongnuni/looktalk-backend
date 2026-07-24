# Look Talk Backend

얼굴 신호 기반 AAC 시스템 **Look Talk**의 백엔드 서버입니다.

---

## Tech Stack

- Java 17
- Spring Boot 4.1.0
- Gradle
- PostgreSQL 17
- Spring Data JPA
- Flyway
- Spring Security
- Swagger / springdoc-openapi
- Docker Compose

---

## Project Structure

```text
src/main/java/com/looktalk/backend
├── global
│   ├── config
│   ├── exception
│   ├── response
│   ├── security
│   └── swagger
├── auth
├── user
├── hospital
├── patient
├── staff
├── device
├── calibration
├── inputsession
├── recommendation
├── chat
└── emergency
````

도메인별 기본 구조는 아래를 따릅니다.

```text
domain
├── controller
├── service
├── dto
├── entity
└── repository
```

---

## Local Setup

### 1. PostgreSQL 실행

```bash
docker compose up -d
```

정상 실행 확인:

```bash
docker ps
```

포트가 아래처럼 보여야 합니다.

```text
0.0.0.0:5433->5432/tcp
```

---

### 2. 서버 실행

Windows PowerShell 기준:

```powershell
.\gradlew bootRun
```

서버 실행 성공 시 아래 로그가 출력됩니다.

```text
Started LooktalkBackendApplication
```

---

## Local URLs

| Name           | URL                                                                            |
| -------------- | ------------------------------------------------------------------------------ |
| Backend Server | [http://localhost:8080](http://localhost:8080)                                 |
| Health Check(테스트용)   | [http://localhost:8080/api/health](http://localhost:8080/api/health)           |
| Swagger UI     | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |
| OpenAPI Docs   | [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)         |
| PostgreSQL     | 127.0.0.1:5433                                                                 |

---

## Database Config

`application-local.yml`의 datasource는 아래 포트를 사용합니다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5433/looktalk
    username: looktalk
    password: looktalk
    driver-class-name: org.postgresql.Driver
```

---

## Build

```powershell
.\gradlew clean build
```

---

## Flyway Rule

Migration 파일 위치:

```text
src/main/resources/db/migration
```

파일명 규칙:

```text
V1__init.sql
V2__create_users.sql
V3__create_patients.sql
```

규칙:

* 이미 공유된 migration 파일은 수정하지 않습니다.
* DB 구조 변경이 필요하면 새 migration 파일을 추가합니다.
* `ddl-auto`는 `validate`로 유지합니다.
* Entity를 수정하면 migration SQL도 함께 작성합니다.

---

## API Response Rule

모든 API 응답은 `ApiResponse<T>` 형식을 사용합니다.

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

Controller에서는 Entity를 직접 반환하지 않고 Response DTO를 반환합니다.

---

## Development Rule

* 공통 설정은 `global` 패키지에 작성합니다.
* 도메인 로직은 각 도메인 패키지 안에 작성합니다.
* Controller에는 복잡한 비즈니스 로직을 작성하지 않습니다.
* 핵심 로직은 Service에 작성합니다.
* Entity는 API 응답으로 직접 반환하지 않습니다.
* DTO는 요청/응답 목적이 드러나게 이름을 짓습니다.

이름 예시:

```text
PatientCreateRequest
PatientResponse
CalibrationSaveRequest
```

---

## Branch / Commit Rule

브랜치는 기능 단위로 생성합니다.

```text
feature/auth
feature/patient
feature/calibration
feature/chat
```

커밋 메시지 예시:

```text
chore: initialize backend project setup
feat: add patient entity
feat: add calibration save api
fix: resolve postgres port conflict
docs: update readme
```


