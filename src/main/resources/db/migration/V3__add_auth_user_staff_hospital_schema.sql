-- ============================================================
-- Look Talk 웹 서비스 - auth / user / staff / hospital 도메인 증분 반영
--
--
-- [이번 마이그레이션에 포함되지 않은 것]
--   staff_patient_assignment — patient 테이블이 아직 존재하지 않아 보류.
--   patient 도메인 마이그레이션 묶음에서 함께 생성 예정. (docs/erd-mapping.md 참고)
-- ============================================================

-- 1. HOSPITAL (신규, 도메인: hospital)
-- [화면 6] 의료진 "내 정보 수정" 화면의 "병원 정보"
-- [화면 3] 의료진 회원가입 화면의 "초대 코드"
CREATE TABLE hospital (
    hospital_id     BIGSERIAL       PRIMARY KEY,
    hospital_name   VARCHAR(100)    NOT NULL,
    invite_code     VARCHAR(50)     NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- 2. STAFF (신규, 도메인: staff)
-- [임시결정 7] users.role 컬럼 + staff/patient 테이블 병행. users와 1:1 확장.
CREATE TABLE staff (
    staff_id        BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(50)     NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    hospital_id     BIGINT          NOT NULL REFERENCES hospital(hospital_id),
    created_at      TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

-- staff.user_id는 UNIQUE 제약으로 인덱스가 이미 생성됨. hospital_id는 별도 생성 필요.
CREATE INDEX ix_staff_hospital_id ON staff (hospital_id);

-- 3. VERIFICATION_CODE (신규, 도메인: auth)
-- [임시결정 2] 이메일/SMS 인증번호를 DB 테이블에 저장 (Redis 등 캐시 계층이 아키텍처에 없음)
CREATE TABLE verification_code (
    verification_code_id   BIGSERIAL       PRIMARY KEY,
    target_type             VARCHAR(10)     NOT NULL,
    target_value             VARCHAR(100)    NOT NULL,
    code                     VARCHAR(10)     NOT NULL,
    expires_at               TIMESTAMPTZ     NOT NULL,
    verified_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_verification_code_target_type CHECK (target_type IN ('EMAIL', 'SMS'))
);

-- [임시결정 D] 인증번호 확인은 반드시 이 조합으로 조회함
CREATE INDEX ix_verification_code_target
    ON verification_code (target_type, target_value);

-- 4. USERS 컬럼 추가 (기존 테이블 — ALTER TABLE만 사용, 기존 컬럼은 변경하지 않음)
-- [임시결정 1] 로그인에 쓰는 "아이디"를 user_email과 별개 필드로 분리
ALTER TABLE users ADD COLUMN login_id VARCHAR(50) UNIQUE;

-- [임시결정 7] 역할 구분 컬럼. 기존 테이블이라 NOT NULL은 걸지 않음 — 추후 백필 후 NOT NULL 전환 필요
ALTER TABLE users ADD COLUMN role VARCHAR(20);
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IS NULL OR role IN ('PATIENT', 'STAFF'));

-- 5. FRIENDSHIP 컬럼 추가 (기존 테이블 — ALTER TABLE만 사용, 기존 컬럼은 변경하지 않음)
-- [임시결정 6] 비회원 친구(전화번호만 아는 상대) 허용. friend_user_id는 기존 정의상 이미 nullable.
ALTER TABLE friendship ADD COLUMN friend_name VARCHAR(50);
ALTER TABLE friendship ADD COLUMN friend_phone VARCHAR(20);

-- [임시결정 C] 일반 UNIQUE 대신 부분 유니크 인덱스 사용.
-- PostgreSQL의 일반 UNIQUE 제약은 NULL을 서로 다른 값으로 취급해 nullable 컬럼 조합에서 무력화되므로,
-- 값이 있는 행에만 유일성을 적용하는 부분 인덱스로 대체함.
CREATE UNIQUE INDEX ux_friendship_owner_phone
    ON friendship (user_id, friend_phone)
    WHERE friend_phone IS NOT NULL;

CREATE UNIQUE INDEX ux_friendship_owner_friend_user
    ON friendship (user_id, friend_user_id)
    WHERE friend_user_id IS NOT NULL;
