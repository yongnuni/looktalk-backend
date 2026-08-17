-- ============================================================
-- Look Talk 웹 서비스
-- 사용자별 공통 gaze calibration + input_session_log 분석 보강 + patient_alias
--
-- 기존 V1~V8 migration은 수정하지 않는다. 본 파일을 V9로 추가한다.
--
-- Calibration은 입력 방식(EYE_TRACKING/BLINK/MOUTH)별 별도 보정값이 아니라
-- 사용자별 공통 gaze mapping(iris -> normalized viewport)이다. 따라서 이 테이블에는
-- input_method/quality_score/viewport_width/height/device_pixel_ratio 등을 두지 않는다.
-- ============================================================


-- ============================================================
-- 1. CALIBRATION
--
-- 사용자별 active gaze calibration은 최대 1개(revoked_at IS NULL)다.
-- calibration_id는 Backend가 생성하는 UUID 문자열이다(DB에서 생성하지 않는다).
-- calibration_data는 Frontend v1 RAW_HOMOGRAPHY + NORMALIZED_VIEWPORT envelope을
-- 그대로 저장하며, 세부 구조 validation은 이후 Calibration Service(Step 5)에서 수행한다.
-- ============================================================

CREATE TABLE calibration (
    calibration_id      VARCHAR(100)    PRIMARY KEY,

    user_id               VARCHAR(50)     NOT NULL
        REFERENCES users(user_id) ON DELETE CASCADE,

    algorithm_version     VARCHAR(100)    NOT NULL,

    calibration_data      JSONB           NOT NULL,

    created_at            TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    revoked_at             TIMESTAMPTZ
);

-- 사용자당 active(revoked_at IS NULL) calibration은 최대 1개임을 DB에서도 방어한다.
-- API 단계의 사용자 단위 lock/serialization은 최종 안전망일 뿐이며, 이 partial unique index가
-- 실제 invariant를 보장한다.
CREATE UNIQUE INDEX ux_calibration_active_user
    ON calibration(user_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE calibration IS
    '사용자별 공통 gaze calibration(iris -> normalized viewport Homography). '
    '입력 방식(EYE_TRACKING/BLINK/MOUTH)별로 분리되지 않는다.';

COMMENT ON COLUMN calibration.calibration_data IS
    'Frontend v1 계약: schemaVersion/mappingType(RAW_HOMOGRAPHY)/coordinateSpace(NORMALIZED_VIEWPORT)/'
    'homography(3x3)/mirrorX/mirrorStrategy/grid/calibratedViewport/reprojectionRmseNormalized. '
    'raw frame, 얼굴 이미지, landmark sequence는 저장하지 않는다.';

COMMENT ON COLUMN calibration.revoked_at IS
    '신규 active calibration 저장 시 기존 active를 revoke 처리한 시각. delete가 아니라 revoke이므로 '
    '과거 input_session_log는 revoked calibration을 계속 historical하게 참조할 수 있다.';


-- ============================================================
-- 2. INPUT_SESSION_LOG 보강
--
-- calibration_id 컬럼(VARCHAR(100))은 V2에서 이미 존재하며 다시 ADD하지 않는다.
-- 여기서는 FK와 두 CHECK, session_type 컬럼만 추가한다.
-- ============================================================

-- 2.1 기존 calibration_id에 FK만 추가한다. Calibration은 정상 흐름에서 delete가 아니라 revoke이므로
-- 이 FK는 예외적으로 calibration row가 삭제되는 경우에 한해 의미를 갖는다. ON DELETE SET NULL로 두어
-- calibration 삭제나 상위 USER-005(환자 탈퇴) cascade가 input_session_log 삭제/보존 흐름을 막지 않게 한다.
ALTER TABLE input_session_log
    ADD CONSTRAINT fk_input_session_log_calibration_id
        FOREIGN KEY (calibration_id) REFERENCES calibration(calibration_id) ON DELETE SET NULL;

-- 2.2 input_method는 V2에서 이미 NOT NULL이지만 CHECK가 없었다. 실제 Java InputMethod enum과 동일하게 고정한다.
ALTER TABLE input_session_log
    ADD CONSTRAINT chk_input_session_log_input_method
        CHECK (input_method IN ('EYE_TRACKING', 'BLINK', 'MOUTH'));

-- 2.3 session_type. 이 migration 적용 시점 기준 input_session_log는 0 rows이므로(Phase 0에서 실측 확인)
-- 임의 backfill 없이 바로 NOT NULL + CHECK로 추가한다. 기존 row가 있었다면 이 방식으로 진행하지 않았을 것이다.
ALTER TABLE input_session_log
    ADD COLUMN session_type VARCHAR(30) NOT NULL;

ALTER TABLE input_session_log
    ADD CONSTRAINT chk_input_session_log_session_type
        CHECK (session_type IN ('CALIBRATION_TEST', 'NORMAL_USE'));

COMMENT ON COLUMN input_session_log.calibration_id IS
    '참조하는 calibration.calibration_id. Calibration은 입력 방식별로 분리되지 않으므로 '
    'EYE_TRACKING/MOUTH/BLINK 세션이 동일 calibration을 참조할 수 있다. '
    'session_type=CALIBRATION_TEST -> 필수, NORMAL_USE -> nullable (Service 단에서 검증, 이 migration의 SQL '
    'CHECK로는 강제하지 않는다).';

COMMENT ON COLUMN input_session_log.session_type IS
    'CALIBRATION_TEST(측정 직후 검사) 또는 NORMAL_USE(일반 사용 중 로그).';


-- ============================================================
-- 3. USER_SETTING 보강
--
-- current_input_method = 사용자가 실제 선택해 현재 사용하는 confirm 방식.
-- recommended_input_method/recommended_at = 최근 확정된 검사/정책 기반 추천 방식(추천이 생겨도
-- current_input_method를 자동 변경하지 않는다). retest_opt_out은 현재 Frontend UX에 없으므로 추가하지 않는다.
-- ============================================================

ALTER TABLE user_setting
    ADD COLUMN recommended_input_method VARCHAR(30),
    ADD COLUMN recommended_at TIMESTAMPTZ;

ALTER TABLE user_setting
    ADD CONSTRAINT chk_user_setting_recommended_input_method
        CHECK (recommended_input_method IS NULL OR recommended_input_method IN ('EYE_TRACKING', 'BLINK', 'MOUTH'));

COMMENT ON COLUMN user_setting.recommended_input_method IS
    'InputMethodRecommendation 결과. NLP WordSuggestion과 무관하다. 값이 생겨도 current_input_method는 '
    '자동으로 바뀌지 않는다.';


-- ============================================================
-- 4. STAFF_PATIENT_ASSIGNMENT.PATIENT_ALIAS
--
-- 특정 STAFF가 특정 PATIENT를 자기 화면에서 보기 위한 개인 별칭. 이번 migration에서는 컬럼만 추가하고
-- Entity/API/Chat 표시명 반영은 Step 8에서 별도로 처리한다.
-- ============================================================

ALTER TABLE staff_patient_assignment
    ADD COLUMN patient_alias VARCHAR(50);

COMMENT ON COLUMN staff_patient_assignment.patient_alias IS
    '해당 STAFF가 자신의 화면에서만 사용하는 환자 별칭. 다른 STAFF/PATIENT에게 노출하지 않는다. '
    'users.user_name을 덮어쓰지 않는다.';
