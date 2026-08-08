# LookTalk Backend Docs

LookTalk Backend의 API 명세와 실제 구현 문서입니다.

## 현재 구현 상태

- REST API 41개 구현 (Auth 9 · User 5 · UserSetting 2 · Staff 4 · E2EE 3 · Memo 4 · Phrase 4 · Friend 4 · Chat 6)
- E2EE(libsodium sealed box) 기반 Memo / Phrase / 병원(HOSPITAL) 채팅 구현
- STOMP 기반 WebSocket 실시간 메시징 구현 (§58)
- SMS 인증번호 발송·SMS 채팅 발송 서버 로직은 구현 완료, 실제 SMS Provider 연동은 아직 미완료
- SMS inbound webhook(§59)은 아직 미구현

## 문서

- [API 명세서](LookTalk_Backend_API_Spec_v2.1.md) — Frontend와 맺는 API 계약(Endpoint, Request/Response, ErrorCode)
- [Database 문서](database.md) — 실제 PostgreSQL 스키마(Flyway V1~V7), ERD, 테이블/제약조건 정리
- [Backend 구현 문서](LookTalk_Backend_Implementation.md) — 실제 코드가 어떻게 구현되어 있는지에 대한 설명(구조, 보안, DB, E2EE, WebSocket 등)
- swagger : 서버 실행 후 'http://localhost:8080/swagger-ui/index.html' 에서 확인 가능