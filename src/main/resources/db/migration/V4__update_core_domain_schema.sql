-- ============================================================
-- Look Talk 웹 서비스 - 현재 구현 범위 DB 보완
--
-- 현재 구현 대상
-- - PATIENT / STAFF 인증
-- - 의료진 - 담당 환자 관계
-- - 이메일 / 전화번호 인증
-- - 개인 메모
-- - 자주 쓰는 문장
-- - 외부 지인 관리
-- - 병원 채팅 / SMS 채팅 기반 구조
--
-- 현재 제외
-- - Calibration
-- - Recommendation
-- - Emergency
-- - Device / Jetson Nano
-- ============================================================


-- ============================================================
-- 1. USERS
--
-- login_id, role은 이전 migration에서 이미 추가됨.
-- 실제 사용자는 반드시 로그인 ID와 역할을 가져야 함.
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE login_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'users.login_id에 NULL 데이터가 존재합니다. 기존 데이터를 먼저 정리해주세요.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        WHERE role IS NULL
    ) THEN
        RAISE EXCEPTION
            'users.role에 NULL 데이터가 존재합니다. 기존 데이터를 먼저 정리해주세요.';
    END IF;
END $$;

ALTER TABLE users
    ALTER COLUMN login_id SET NOT NULL;

ALTER TABLE users
    ALTER COLUMN role SET NOT NULL;


-- ============================================================
-- 2. STAFF - PATIENT ASSIGNMENT
--
-- patient 별도 테이블은 현재 생성하지 않음.
-- users.role = 'PATIENT'인 user를 환자로 사용.
--
-- 하나의 의료진 → 여러 환자 가능
-- 하나의 환자 → 여러 의료진 가능
-- ============================================================

CREATE TABLE staff_patient_assignment (
    assignment_id BIGSERIAL PRIMARY KEY,

    staff_id BIGINT NOT NULL,
    patient_user_id VARCHAR(50) NOT NULL,

    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_staff_patient_assignment_staff
        FOREIGN KEY (staff_id)
        REFERENCES staff(staff_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_staff_patient_assignment_patient
        FOREIGN KEY (patient_user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_staff_patient_assignment
        UNIQUE (staff_id, patient_user_id)
);

CREATE INDEX ix_staff_patient_assignment_staff_id
    ON staff_patient_assignment(staff_id);

CREATE INDEX ix_staff_patient_assignment_patient_user_id
    ON staff_patient_assignment(patient_user_id);


-- ============================================================
-- 3. VERIFICATION CODE
--
-- 인증번호 사용 목적 구분
-- SIGNUP         : 회원가입
-- PASSWORD_RESET : 비밀번호 재설정
-- PHONE_VERIFY   : 전화번호 인증 / SMS 연동
-- ============================================================

ALTER TABLE verification_code
    ADD COLUMN purpose VARCHAR(30);

-- 개발 DB에 기존 테스트 데이터가 존재하는 경우를 위한 임시 백필.
-- 실제 의미 있는 데이터가 있다면 실행 전에 용도별로 구분해야 함.
UPDATE verification_code
SET purpose = 'SIGNUP'
WHERE purpose IS NULL;

ALTER TABLE verification_code
    ALTER COLUMN purpose SET NOT NULL;

ALTER TABLE verification_code
    ADD CONSTRAINT chk_verification_code_purpose
    CHECK (
        purpose IN (
            'SIGNUP',
            'PASSWORD_RESET',
            'PHONE_VERIFY'
        )
    );

CREATE INDEX ix_verification_code_lookup
    ON verification_code (
        target_type,
        target_value,
        purpose
    );


-- ============================================================
-- 4. MEMO
--
-- 개인 메모는 반드시 사용자와 내용을 가짐.
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM memo
        WHERE user_id IS NULL
           OR content IS NULL
    ) THEN
        RAISE EXCEPTION
            'memo에 user_id 또는 content가 NULL인 데이터가 존재합니다.';
    END IF;
END $$;

ALTER TABLE memo
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE memo
    ALTER COLUMN content SET NOT NULL;

CREATE INDEX ix_memo_user_id
    ON memo(user_id);


-- ============================================================
-- 5. PHRASE
--
-- phrase_text는 기존 테이블 생성 시 이미 NOT NULL.
-- user_id만 필수화.
-- 최대 3개 제한은 Service에서 처리.
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM phrase
        WHERE user_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'phrase에 user_id가 NULL인 데이터가 존재합니다.';
    END IF;
END $$;

ALTER TABLE phrase
    ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX ix_phrase_user_id
    ON phrase(user_id);


-- ============================================================
-- 6. FRIENDSHIP
--
-- 회원 친구:
--   friend_user_id 사용
--
-- 외부 SMS 지인:
--   friend_user_id = NULL
--   friend_name / friend_phone 사용
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM friendship
        WHERE user_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'friendship에 user_id가 NULL인 데이터가 존재합니다.';
    END IF;
END $$;

ALTER TABLE friendship
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE friendship
    ADD CONSTRAINT chk_friendship_target
    CHECK (
        friend_user_id IS NOT NULL
        OR friend_phone IS NOT NULL
    );

CREATE INDEX ix_friendship_user_id
    ON friendship(user_id);


-- ============================================================
-- 7. CHAT ROOM
--
-- 앞으로 room_type을 명시적으로 지정.
--
-- 예:
-- HOSPITAL : 환자 - 의료진 병원 채팅
-- SMS      : 환자 - 외부 지인 SMS 채팅
--
-- 기존 DIRECT 데이터가 있을 수 있으므로
-- 아직 CHECK 제약은 걸지 않음.
-- ============================================================

ALTER TABLE chat_room
    ALTER COLUMN room_type DROP DEFAULT;

ALTER TABLE chat_room
    ALTER COLUMN room_type SET NOT NULL;


-- ============================================================
-- 8. CHAT PARTICIPANT
--
-- 회원:
--   user_id 사용
--
-- 외부 지인:
--   user_id = NULL
--   external_name / external_phone 사용
-- ============================================================

ALTER TABLE chat_participant
    ADD CONSTRAINT chk_chat_participant_identity
    CHECK (
        user_id IS NOT NULL
        OR external_phone IS NOT NULL
    );

CREATE UNIQUE INDEX ux_chat_participant_room_user
    ON chat_participant(room_id, user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX ux_chat_participant_room_external_phone
    ON chat_participant(room_id, external_phone)
    WHERE external_phone IS NOT NULL;

CREATE INDEX ix_chat_participant_room_id
    ON chat_participant(room_id);


-- ============================================================
-- 9. MESSAGE
--
-- 현재는 기존 message 구조를 그대로 사용.
--
-- SMS 업체 선정 후 필요 시 다음 migration에서 추가:
-- - external_message_id
-- - delivery_status
-- - provider 관련 식별자
-- ============================================================

CREATE INDEX ix_message_room_created_at
    ON message(room_id, created_at);
