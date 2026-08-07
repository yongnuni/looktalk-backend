-- ============================================================
-- Look Talk 웹 서비스 - 초기 도메인 스키마 (PostgreSQL)
-- 대상: users, user_setting, phrase, friendship, memo,
--       chat_room, chat_participant, message, input_session_log
--
-- [재확인 필요한 미해결 사항]
--   - AI 추천 결과 저장 테이블 여부
--   - NLP 자동완성 개인화 데이터 저장 방식
--   - 캘리브레이션 원본 데이터(JSON) 저장 여부
--   - 의료진/환자 역할(role) 구분 컬럼 필요 여부
--
-- [이 마이그레이션에 포함되지 않은 도메인]
--   hospital, patient, staff, device, calibration, recommendation, emergency
--   -> 설계 확정되는 대로 별도 V3, V4 ... 마이그레이션으로 추가
-- ============================================================

-- 1. USERS
CREATE TABLE users (
    user_id             VARCHAR(50)     PRIMARY KEY,
    user_name           VARCHAR(50)     NOT NULL,
    user_email          VARCHAR(100)    NOT NULL UNIQUE,
    user_pwd            VARCHAR(255)    NOT NULL,
    user_phone          VARCHAR(20),
    profile_image       TEXT,
    is_email_verified   BOOLEAN         DEFAULT FALSE,
    is_sms_verified     BOOLEAN         DEFAULT FALSE,
    reset_token         VARCHAR(255),
    reset_token_expiry  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN users.reset_token IS '비밀번호 재설정 토큰 (해시 저장 권장)';
COMMENT ON COLUMN users.reset_token_expiry IS '재설정 토큰 만료 일시';

-- 2. USER_SETTING (users와 1:1 관계)
CREATE TABLE user_setting (
    user_id                VARCHAR(50)     PRIMARY KEY
                                            REFERENCES users(user_id) ON DELETE CASCADE,
    keyboard_layout        VARCHAR(20)     DEFAULT 'QWERTY',
    is_key_enlarged        BOOLEAN         DEFAULT FALSE,
    current_input_method   VARCHAR(30)     DEFAULT 'EYE_TRACKING',
    updated_at             TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 3. PHRASE (자주 쓰는 문장)
CREATE TABLE phrase (
    phrase_id       BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(50)     REFERENCES users(user_id) ON DELETE CASCADE,
    phrase_text     TEXT            NOT NULL,
    category        VARCHAR(30),
    created_at      TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 4. FRIENDSHIP (친구 관계)
CREATE TABLE friendship (
    friendship_id   BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(50)     REFERENCES users(user_id) ON DELETE CASCADE,
    friend_user_id  VARCHAR(50)     REFERENCES users(user_id) ON DELETE CASCADE,
    status          VARCHAR(20)     DEFAULT 'ACCEPTED',
    created_at      TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 5. MEMO (개인 메모장)
CREATE TABLE memo (
    memo_id     BIGSERIAL       PRIMARY KEY,
    user_id     VARCHAR(50)     REFERENCES users(user_id) ON DELETE CASCADE,
    content     TEXT,
    created_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 6. CHAT_ROOM (채팅방)
CREATE TABLE chat_room (
    room_id     BIGSERIAL       PRIMARY KEY,
    room_type   VARCHAR(20)     DEFAULT 'DIRECT',
    created_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 7. CHAT_PARTICIPANT (채팅방 참여자 - 회원/비회원 모두 포함)
CREATE TABLE chat_participant (
    participant_id  BIGSERIAL       PRIMARY KEY,
    room_id         BIGINT          NOT NULL REFERENCES chat_room(room_id) ON DELETE CASCADE,
    user_id         VARCHAR(50)     REFERENCES users(user_id) ON DELETE SET NULL,
    external_name   VARCHAR(50),
    external_phone  VARCHAR(20),
    joined_at       TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN chat_participant.user_id IS '가입 회원 ID (외부인인 경우 NULL / 회원 탈퇴 시에도 NULL 처리되어 채팅 기록은 보존됨)';

-- 8. MESSAGE (채팅 메시지)
CREATE TABLE message (
    message_id              BIGSERIAL       PRIMARY KEY,
    room_id                 BIGINT          NOT NULL REFERENCES chat_room(room_id) ON DELETE CASCADE,
    sender_participant_id   BIGINT          NOT NULL REFERENCES chat_participant(participant_id) ON DELETE CASCADE,
    content                 TEXT            NOT NULL,
    message_type            VARCHAR(20)     DEFAULT 'TEXT',
    created_at              TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 9. INPUT_SESSION_LOG (입력 방식/레이아웃별 분석 로그)
CREATE TABLE input_session_log (
    log_id                  VARCHAR(100)    PRIMARY KEY,
    user_id                 VARCHAR(50)     REFERENCES users(user_id) ON DELETE CASCADE,
    calibration_id          VARCHAR(100),
    input_method            VARCHAR(30)     NOT NULL,
    keyboard_layout         VARCHAR(20)     NOT NULL,
    typo_rate               REAL,
    input_speed             REAL,
    recognition_accuracy    REAL,
    input_stability         REAL,
    session_at              TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);
