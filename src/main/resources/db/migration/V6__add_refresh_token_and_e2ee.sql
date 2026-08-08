-- ============================================================
-- Look Talk 웹 서비스
-- Refresh Token / E2EE 저장 구조 반영
--
-- 기존 V1~V4 migration은 수정하지 않는다.
-- 본 파일을 다음 Flyway migration(V5)으로 추가한다.
--
-- 적용 전 주의:
-- memo / phrase / message에 기존 평문 데이터가 있으면
-- 자동으로 E2EE ciphertext로 변환할 수 없으므로 migration을 중단한다.
-- 개발용 테스트 데이터라면 별도로 삭제한 뒤 다시 실행하고,
-- 보존해야 하는 데이터라면 클라이언트 키를 이용한 별도 마이그레이션이 필요하다.
-- ============================================================


-- ============================================================
-- 0. 기존 평문 사용자 콘텐츠 존재 여부 확인
-- ============================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM memo LIMIT 1) THEN
        RAISE EXCEPTION
            'memo에 기존 데이터가 존재합니다. E2EE 적용 전에 기존 평문 메모를 삭제하거나 별도로 암호화 이전해야 합니다.';
    END IF;

    IF EXISTS (SELECT 1 FROM phrase LIMIT 1) THEN
        RAISE EXCEPTION
            'phrase에 기존 데이터가 존재합니다. E2EE 적용 전에 기존 평문 문장을 삭제하거나 별도로 암호화 이전해야 합니다.';
    END IF;

    IF EXISTS (SELECT 1 FROM message LIMIT 1) THEN
        RAISE EXCEPTION
            'message에 기존 데이터가 존재합니다. E2EE 적용 전에 기존 평문 메시지를 삭제하거나 별도로 암호화 이전해야 합니다.';
    END IF;
END $$;


-- ============================================================
-- 1. 불필요한 개발용 테이블 제거
-- ============================================================

DROP TABLE IF EXISTS app_health_check;


-- ============================================================
-- 2. REFRESH_TOKEN
--
-- Access Token 재발급에 사용하는 Refresh Token을 관리한다.
-- 토큰 원문은 저장하지 않고 SHA-256 등의 해시값만 저장한다.
-- ============================================================

CREATE TABLE refresh_token (
    refresh_token_id BIGSERIAL PRIMARY KEY,

    user_id           VARCHAR(50) NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,

    token_hash        VARCHAR(64) NOT NULL UNIQUE,

    expires_at        TIMESTAMPTZ NOT NULL,

    revoked_at        TIMESTAMPTZ,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_refresh_token_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_token_user_id
    ON refresh_token(user_id);

CREATE INDEX ix_refresh_token_active_user
    ON refresh_token(user_id, expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE refresh_token IS
    'Access Token 재발급용 Refresh Token 관리. 원문 토큰은 저장하지 않고 해시값만 저장한다.';

COMMENT ON COLUMN refresh_token.token_hash IS
    'Refresh Token 원문의 SHA-256 등 단방향 해시값';


-- ============================================================
-- 3. USER_E2EE_KEY
--
-- 사용자별 E2EE 공개키를 저장한다.
-- 개인키는 서버 또는 DB에 저장하지 않는다.
-- key_version으로 향후 키 교체를 지원한다.
-- ============================================================

CREATE TABLE user_e2ee_key (
    key_id        BIGSERIAL PRIMARY KEY,

    user_id       VARCHAR(50) NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,

    key_version   INTEGER NOT NULL,

    public_key    TEXT NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    revoked_at    TIMESTAMPTZ,

    CONSTRAINT uq_user_e2ee_key_version
        UNIQUE (user_id, key_version),

    CONSTRAINT chk_user_e2ee_key_version
        CHECK (key_version >= 1),

    CONSTRAINT chk_user_e2ee_public_key_not_blank
        CHECK (BTRIM(public_key) <> '')
);

CREATE INDEX ix_user_e2ee_key_active
    ON user_e2ee_key(user_id, key_version DESC)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE user_e2ee_key IS
    'Look Talk 사용자 E2EE 공개키. 개인키는 클라이언트에서만 보관한다.';

COMMENT ON COLUMN user_e2ee_key.public_key IS
    'libsodium sealed box에서 사용할 Base64 인코딩 공개키';

COMMENT ON COLUMN user_e2ee_key.key_version IS
    '암호화 데이터가 어떤 공개키 버전을 사용했는지 식별하는 버전';


-- ============================================================
-- 4. MEMO E2EE 저장 구조
--
-- 기존 content 컬럼을 그대로 사용하되 평문이 아니라
-- Base64 ciphertext만 저장한다.
-- ============================================================

ALTER TABLE memo
    ADD COLUMN key_version INTEGER NOT NULL,
    ADD COLUMN encryption_algorithm VARCHAR(40) NOT NULL
        DEFAULT 'LIBSODIUM_SEALED_BOX';

ALTER TABLE memo
    ADD CONSTRAINT chk_memo_key_version
        CHECK (key_version >= 1);

ALTER TABLE memo
    ADD CONSTRAINT chk_memo_encryption_algorithm_not_blank
        CHECK (BTRIM(encryption_algorithm) <> '');

COMMENT ON COLUMN memo.content IS
    '평문 메모가 아닌 E2EE Base64 ciphertext';

COMMENT ON COLUMN memo.key_version IS
    '메모 암호화에 사용한 user_e2ee_key.key_version';

COMMENT ON COLUMN memo.encryption_algorithm IS
    '메모 암호화 알고리즘. 현재 LIBSODIUM_SEALED_BOX 사용';


-- ============================================================
-- 5. PHRASE E2EE 저장 구조
--
-- 기존 phrase_text 컬럼을 그대로 사용하되 평문이 아니라
-- Base64 ciphertext만 저장한다.
--
-- 자주 쓰는 문장 최대 3개 및 4번째 등록 시
-- 가장 오래된 문장 자동 삭제 정책은 Service에서 처리한다.
-- ============================================================

ALTER TABLE phrase
    ADD COLUMN key_version INTEGER NOT NULL,
    ADD COLUMN encryption_algorithm VARCHAR(40) NOT NULL
        DEFAULT 'LIBSODIUM_SEALED_BOX';

ALTER TABLE phrase
    ADD CONSTRAINT chk_phrase_key_version
        CHECK (key_version >= 1);

ALTER TABLE phrase
    ADD CONSTRAINT chk_phrase_encryption_algorithm_not_blank
        CHECK (BTRIM(encryption_algorithm) <> '');

COMMENT ON COLUMN phrase.phrase_text IS
    '평문 문장이 아닌 E2EE Base64 ciphertext';

COMMENT ON COLUMN phrase.key_version IS
    '문장 암호화에 사용한 user_e2ee_key.key_version';

COMMENT ON COLUMN phrase.encryption_algorithm IS
    '문장 암호화 알고리즘. 현재 LIBSODIUM_SEALED_BOX 사용';


-- ============================================================
-- 6. MESSAGE 구조 변경
--
-- message 테이블은 메시지 메타데이터만 보관한다.
-- 평문 content 컬럼은 더 이상 사용하지 않으므로 제거한다.
-- 실제 메시지 본문은 message_ciphertext에 저장한다.
-- ============================================================

ALTER TABLE message
    DROP COLUMN content;

COMMENT ON TABLE message IS
    '채팅 메시지 메타데이터. 실제 메시지 본문은 message_ciphertext에 암호문으로 저장한다.';


-- ============================================================
-- 7. MESSAGE_CIPHERTEXT
--
-- 내부 병원 채팅:
-- 동일한 평문을 각 participant의 공개키로 각각 암호화하여
-- recipient_user_id별 ciphertext를 저장한다.
--
-- SMS 채팅:
-- 환자 본인이 이후 대화 이력을 복호화할 수 있도록
-- 환자 공개키로 암호화한 ciphertext를 저장한다.
-- ============================================================

CREATE TABLE message_ciphertext (
    message_ciphertext_id BIGSERIAL PRIMARY KEY,

    message_id             BIGINT NOT NULL
        REFERENCES message(message_id) ON DELETE CASCADE,

    recipient_user_id      VARCHAR(50) NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,

    key_version            INTEGER NOT NULL,

    encryption_algorithm   VARCHAR(40) NOT NULL
        DEFAULT 'LIBSODIUM_SEALED_BOX',

    ciphertext             TEXT NOT NULL,

    CONSTRAINT uq_message_ciphertext_recipient
        UNIQUE (message_id, recipient_user_id),

    CONSTRAINT chk_message_ciphertext_key_version
        CHECK (key_version >= 1),

    CONSTRAINT chk_message_ciphertext_algorithm_not_blank
        CHECK (BTRIM(encryption_algorithm) <> ''),

    CONSTRAINT chk_message_ciphertext_not_blank
        CHECK (BTRIM(ciphertext) <> '')
);

CREATE INDEX ix_message_ciphertext_recipient_user_id
    ON message_ciphertext(recipient_user_id);

COMMENT ON TABLE message_ciphertext IS
    '사용자별 E2EE 채팅 ciphertext. Backend는 평문 메시지를 저장하지 않는다.';

COMMENT ON COLUMN message_ciphertext.recipient_user_id IS
    '해당 ciphertext를 복호화할 수 있는 Look Talk 사용자';

COMMENT ON COLUMN message_ciphertext.key_version IS
    'recipient 사용자의 공개키 버전';

COMMENT ON COLUMN message_ciphertext.ciphertext IS
    'libsodium sealed box 결과를 Base64로 인코딩한 암호문';


-- ============================================================
-- 8. MESSAGE sender와 room 관계 무결성 강화
--
-- sender_participant_id가 실제 동일 room의 participant인지
-- DB에서도 보장한다.
-- ============================================================

ALTER TABLE chat_participant
    ADD CONSTRAINT uq_chat_participant_id_room
        UNIQUE (participant_id, room_id);

ALTER TABLE message
    DROP CONSTRAINT IF EXISTS message_sender_participant_id_fkey;

ALTER TABLE message
    ADD CONSTRAINT fk_message_sender_same_room
        FOREIGN KEY (sender_participant_id, room_id)
        REFERENCES chat_participant(participant_id, room_id)
        ON DELETE CASCADE;


-- ============================================================
-- 9. 컬럼 설명 보완
-- ============================================================

COMMENT ON COLUMN users.user_pwd IS
    '사용자 비밀번호 BCrypt 해시. 평문 비밀번호를 저장하지 않는다.';

COMMENT ON COLUMN users.user_email IS
    '회원가입 이메일 인증 및 비밀번호 재설정에 사용하는 이메일';

COMMENT ON COLUMN users.user_phone IS
    '본인 SMS 인증이 완료된 전화번호';

COMMENT ON COLUMN users.user_name IS
    '사용자가 마이페이지에서 선택적으로 설정하는 표시 이름. NULL인 경우 API에서는 login_id를 displayName으로 사용한다.';


-- ============================================================
-- 10. CHAT_ROOM 대화방 중복 방지
--
-- Service에서 아래 형식으로 conversation_key를 생성한다.
--
-- HOSPITAL:{작은 userId}:{큰 userId}
-- SMS:{currentUserId}:{normalizedFriendPhone}
-- ============================================================

ALTER TABLE chat_room
    ADD COLUMN conversation_key VARCHAR(200);

CREATE UNIQUE INDEX ux_chat_room_conversation_key
    ON chat_room(conversation_key)
    WHERE conversation_key IS NOT NULL;

COMMENT ON COLUMN chat_room.conversation_key IS
    '1:1 채팅방 중복 생성을 막기 위한 비즈니스 키';


-- ============================================================
-- 11. VERIFICATION_CODE 인증번호 해시 저장
--
-- 이메일/SMS 인증번호 원문은 DB에 저장하지 않는다.
-- 서버 비밀키를 이용한 HMAC-SHA256 결과만 저장한다.
--
-- verification_code는 단기 인증용 데이터이므로
-- 기존 개발용 인증번호 레코드는 새 해시 정책 적용 전에 제거한다.
-- ============================================================

DELETE FROM verification_code;

ALTER TABLE verification_code
    RENAME COLUMN code TO code_hash;

ALTER TABLE verification_code
    ALTER COLUMN code_hash TYPE VARCHAR(64);

COMMENT ON COLUMN verification_code.code_hash IS
    '이메일/SMS 인증번호의 HMAC-SHA256 검증값';


-- ============================================================
-- 12. 정책상 DB 변경이 필요하지 않은 항목
--
-- 아래 항목은 Service/API 로직에서 처리한다.
-- ============================================================

-- - 환자/의료진 모두 login_id + password 로그인
-- - user_name 미설정 시 API displayName = login_id
-- - 의료진 탈퇴 시 담당 환자 존재 여부로 탈퇴 차단하지 않음
-- - staff_patient_assignment는 채팅 권한에 사용하지 않음
-- - 병원 채팅 친구 탭은 PATIENT / STAFF 전체 회원 조회
-- - 개인 PATIENT도 hospital 연결 없이 사용 가능
-- - 친구 등록 전 users.is_sms_verified = TRUE 검사
-- - 친구 등록 후 GET /api/friends에 자동 표시
-- - SMS 친구 클릭 시 chat_room find-or-create
-- - 내부 회원 클릭 시 HOSPITAL chat_room find-or-create
-- - Phrase 최대 3개 유지
-- - Phrase 4번째 등록 시 created_at이 가장 오래된 행 삭제 후 INSERT