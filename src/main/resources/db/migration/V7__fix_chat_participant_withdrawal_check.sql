-- ============================================================
-- Look Talk 웹 서비스
-- USER-005 회원탈퇴 + chat_participant CHECK 제약 충돌 수정
--
-- 기존 V1~V6 migration은 수정하지 않는다. 본 파일을 V7로 추가한다.
--
-- [문제]
-- users DELETE 시 chat_participant.user_id는 ON DELETE SET NULL(V2)로 정리되어 chat_room/message
-- 이력을 보존하도록 설계되어 있었으나, 내부 participant는 external_phone이 항상 NULL이므로
-- user_id까지 NULL이 되는 순간 chk_chat_participant_identity(V4: user_id IS NOT NULL OR
-- external_phone IS NOT NULL)를 위반해 회원탈퇴(USER-005) 전체가 500으로 rollback된다.
-- (실제 HTTP/DB로 재현됨.)
--
-- [해결]
-- is_withdrawn 컬럼을 추가해 "탈퇴한 내부 participant" 상태를 명시적으로 표현하고,
-- 다음 세 가지 상태만 허용하도록 identity CHECK를 교체한다.
--   1) 정상 내부 participant: user_id NOT NULL, external_phone NULL, is_withdrawn = FALSE
--   2) 외부 SMS participant : user_id NULL,     external_phone NOT NULL, is_withdrawn = FALSE
--   3) 탈퇴한 내부 participant: user_id NULL,   external_phone NULL, is_withdrawn = TRUE
-- participant 행 자체는 삭제하지 않아 chat_room/message 이력 보존 정책을 그대로 유지한다.
--
-- V4의 partial UNIQUE 인덱스(ux_chat_participant_room_user, ux_chat_participant_room_external_phone)와
-- V6의 uq_chat_participant_id_room / message FK는 user_id·external_phone 기준으로 동작하므로
-- is_withdrawn 추가와 무관하게 그대로 유지된다.
-- ============================================================

ALTER TABLE chat_participant
    ADD COLUMN is_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN chat_participant.is_withdrawn IS
    '내부 회원이 탈퇴해 user_id가 NULL로 정리된 participant인지 여부. chat_room/message 이력을 보존하기 '
    '위해 participant 행 자체는 삭제하지 않는다.';

ALTER TABLE chat_participant
    DROP CONSTRAINT chk_chat_participant_identity;

ALTER TABLE chat_participant
    ADD CONSTRAINT chk_chat_participant_identity
    CHECK (
        (user_id IS NOT NULL AND external_phone IS NULL AND is_withdrawn = FALSE)
        OR (user_id IS NULL AND external_phone IS NOT NULL AND is_withdrawn = FALSE)
        OR (user_id IS NULL AND external_phone IS NULL AND is_withdrawn = TRUE)
    );
