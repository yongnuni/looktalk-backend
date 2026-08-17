-- ============================================================
-- Look Talk 웹 서비스
-- Emergency 호출 신설 + chat_room / input_session_log 무결성 보강
--
-- 기존 V1~V7 migration은 수정하지 않는다. 본 파일을 V8로 추가한다.
-- ============================================================


-- ============================================================
-- 1. EMERGENCY_CALL
--
-- 호출 1회당 1행 저장한다. 반복 횟수 컬럼은 두지 않고 조회 시 집계한다.
--
-- read_by_staff_id는 ON DELETE SET NULL로 두어, 호출을 읽은 STAFF가
-- 이후 회원탈퇴(USER-005)하더라도 그 사실이 탈퇴를 막지 않고
-- emergency_call 행 자체(및 read_at)는 그대로 보존되도록 한다.
-- ============================================================

CREATE TABLE emergency_call (
    emergency_call_id  BIGSERIAL       PRIMARY KEY,

    patient_user_id     VARCHAR(50)     NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,

    called_at            TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    read_at               TIMESTAMPTZ,

    read_by_staff_id     BIGINT
        REFERENCES staff(staff_id) ON DELETE SET NULL
);

CREATE INDEX ix_emergency_call_patient_user_id
    ON emergency_call(patient_user_id);

COMMENT ON TABLE emergency_call IS
    '환자 긴급 호출 이력. 호출 1회당 1행이며 별도 count 컬럼을 두지 않는다.';

COMMENT ON COLUMN emergency_call.patient_user_id IS
    '호출한 환자. 환자 탈퇴(USER-005) 시 호출 이력도 함께 CASCADE 삭제된다.';

COMMENT ON COLUMN emergency_call.read_by_staff_id IS
    '최초로 이 호출을 읽음 처리한 STAFF. 이후 다른 STAFF의 읽음 요청은 이 값을 덮어쓰지 않는다. '
    '해당 STAFF가 탈퇴해도 회원탈퇴를 막지 않도록 ON DELETE SET NULL로 둔다.';


-- ============================================================
-- 2. CHAT_ROOM.ROOM_TYPE CHECK
--
-- V4에서 NOT NULL로 전환되었으나 CHECK 제약은 없었다(당시 DIRECT 데이터
-- 잔존 가능성 때문에 보류). 이제 Backend가 실제로 생성하는 값을
-- Java ChatRoomType enum과 동일하게 고정한다.
-- ============================================================

ALTER TABLE chat_room
    ADD CONSTRAINT chk_chat_room_room_type
    CHECK (room_type IN ('HOSPITAL', 'SMS'));


-- ============================================================
-- 3. INPUT_SESSION_LOG.USER_ID INDEX
--
-- V2에서 FK만 생성되고 조회용 index는 없었다.
-- ============================================================

CREATE INDEX ix_input_session_log_user_id
    ON input_session_log(user_id);
