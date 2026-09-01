# LookTalk Backend Docs

LookTalk Backend의 API 명세와 실제 구현 문서입니다.

## 현재 구현 상태

- REST API 41개 구현 (Auth 9 · User 5 · UserSetting 2 · Staff 4 · E2EE 3 · Memo 4 · Phrase 4 · Friend 4 · Chat 6)
- E2EE(libsodium sealed box) 기반 Memo / Phrase / 병원(HOSPITAL) 채팅 구현
- STOMP 기반 WebSocket 실시간 메시징 구현 (§58)
- SMS 인증번호 발송·SMS 채팅 발송(SOLAPI outbound) 및 LG U+ MessageHub MO inbound webhook(§59) 구현 완료
- 로컬 환경에서 Cloudflare Tunnel로 실제 휴대폰과 양방향 SMS를 테스트하는 방법은 [SMS 로컬 데모 가이드](LookTalk_Backend_Sms_Local_Demo.md) 참고

## 문서

- [API 명세서](LookTalk_Backend_API_Spec_v2.1.md) — Frontend와 맺는 API 계약(Endpoint, Request/Response, ErrorCode)
- [Database 문서](database.md) — 실제 PostgreSQL 스키마(Flyway V1~V7), ERD, 테이블/제약조건 정리
- [Backend 구현 문서](LookTalk_Backend_Implementation.md) — 실제 코드가 어떻게 구현되어 있는지에 대한 설명(구조, 보안, DB, E2EE, WebSocket 등)
- [SMS 로컬 데모 가이드](LookTalk_Backend_Sms_Local_Demo.md) — 배포 없이 로컬(Cloudflare Tunnel)에서 SOLAPI outbound/U+ MO inbound를 실제 휴대폰과 양방향으로 테스트하는 방법
- swagger : 서버 실행 후 'http://localhost:8080/swagger-ui/index.html' 에서 확인 가능