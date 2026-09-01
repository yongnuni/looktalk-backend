-- SOLAPI(outbound) / LG U+ 메시지허브 MO(inbound)를 실제 서비스 수준으로 연동하기 위한 신규 테이블.
-- 기존 message/message_ciphertext는 HOSPITAL과 SMS가 공유하므로 SMS 전용 상태 컬럼을 여기 추가하지 않고
-- 별도 테이블로 분리한다. 기존 migration은 수정하지 않는다.

-- 1. 환자별 SMS 회신 수신용 서비스 번호(U+ MO 수신번호) 배정.
-- "환자 A -> 070XXXXXXXX" 매핑이 있어야 친구의 답장(moNumber)으로 환자를 역으로 찾을 수 있다.
CREATE TABLE sms_inbound_number_assignment (
    assignment_id   BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(50)     NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    provider        VARCHAR(20)     NOT NULL,
    service_number  VARCHAR(20)     NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    activated_at    TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deactivated_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sms_inbound_assignment_provider CHECK (provider IN ('UPLUS_MO'))
);

-- 동일 active service_number가 동시에 여러 user에게 배정되는 것을 막는다(inbound 매칭 유일성의 최종 방어선).
CREATE UNIQUE INDEX ux_sms_inbound_assignment_active_service_number
    ON sms_inbound_number_assignment (service_number)
    WHERE is_active;

-- 한 환자는 active 번호를 최대 1개만 가진다는 것을 기본 전제로 한다. 번호를 교체할 때는 기존 행을 비활성화하고
-- 새 행을 추가해 history를 보존한다.
CREATE UNIQUE INDEX ux_sms_inbound_assignment_active_user
    ON sms_inbound_number_assignment (user_id)
    WHERE is_active;

COMMENT ON TABLE sms_inbound_number_assignment IS
    '환자(user)에게 배정된 SMS 회신 수신용 서비스 번호(U+ MO 수신번호) 이력. 번호 자체의 구매/발급은 외부 계약이며 '
    '여기서는 "이미 확보한 번호" <-> "Look Talk 환자" 매핑만 관리한다.';

-- 2. 친구 SMS(outbound) 발송의 Provider 발송/배달 상태. message/message_ciphertext와 1:1로 연결된다.
CREATE TABLE sms_message_delivery (
    sms_delivery_id          BIGSERIAL       PRIMARY KEY,
    message_id               BIGINT          NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
    provider                 VARCHAR(20)     NOT NULL,
    provider_message_id      VARCHAR(100),
    delivery_status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    provider_status_code     VARCHAR(20),
    provider_status_message  VARCHAR(200),
    delivered_at             TIMESTAMPTZ,
    created_at                TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sms_message_delivery_provider CHECK (provider IN ('SOLAPI')),
    CONSTRAINT chk_sms_message_delivery_status
        CHECK (delivery_status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED'))
);

-- SMS message는 항상 정확히 하나의 delivery 행을 갖는다(1:1).
CREATE UNIQUE INDEX ux_sms_message_delivery_message ON sms_message_delivery (message_id);

-- SOLAPI SINGLE-REPORT webhook이 provider messageId로 이 행을 역조회할 때 사용한다. 발송 요청 직후(PENDING)에는
-- provider_message_id가 아직 없을 수 있으므로 NULL은 유니크 제약에서 제외한다.
CREATE UNIQUE INDEX ux_sms_message_delivery_provider_message_id
    ON sms_message_delivery (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

COMMENT ON TABLE sms_message_delivery IS
    'SMS 친구채팅(outbound) 메시지의 Provider 발송/배달 상태. HOSPITAL 메시지에는 이 행이 없다.';

-- 3. U+ MO inbound 처리 이력(idempotency 전용). 평문 SMS 본문(moMsg)은 여기에도, 다른 어떤 테이블에도 저장하지
-- 않는다 - 실제 본문은 message_ciphertext에 암호문으로만 남는다.
CREATE TABLE sms_inbound_receipt (
    inbound_receipt_id   BIGSERIAL       PRIMARY KEY,
    provider              VARCHAR(20)     NOT NULL,
    external_message_id   VARCHAR(100)    NOT NULL,
    service_number        VARCHAR(20)     NOT NULL,
    sender_phone           VARCHAR(20)     NOT NULL,
    message_id             BIGINT          REFERENCES message(message_id) ON DELETE SET NULL,
    processing_status      VARCHAR(30)     NOT NULL,
    received_at             TIMESTAMPTZ     NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sms_inbound_receipt_provider CHECK (provider IN ('UPLUS_MO')),
    CONSTRAINT chk_sms_inbound_receipt_status CHECK (processing_status IN (
        'PROCESSED', 'REJECTED_UNKNOWN_NUMBER', 'REJECTED_UNKNOWN_FRIEND', 'REJECTED_NO_E2EE_KEY'
    ))
);

-- U+ MO는 동일 moKey를 재전송할 수 있다(네트워크 재시도 등). provider+external_message_id(moKey) 조합으로
-- 이미 처리된 요청을 감지해 Message를 중복 생성하지 않는다.
CREATE UNIQUE INDEX ux_sms_inbound_receipt_provider_external_id
    ON sms_inbound_receipt (provider, external_message_id);

COMMENT ON TABLE sms_inbound_receipt IS
    'U+ MO inbound SMS 처리 이력(idempotency). moMsg 평문은 어떤 컬럼에도 저장하지 않는다.';

-- 4. message - client-generated idempotency key. SMS 전용이 아니라 어떤 채팅 메시지 전송에도 재사용 가능한
-- 범용 필드이므로 message 테이블에 직접 추가한다(HOSPITAL/SMS 공용, SMS 전용 컬럼 아님).
ALTER TABLE message ADD COLUMN client_message_id VARCHAR(100);

CREATE UNIQUE INDEX ux_message_client_message_id
    ON message (room_id, sender_participant_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

COMMENT ON COLUMN message.client_message_id IS
    '클라이언트가 생성한 요청 idempotency key(선택). 동일 room+sender+key 재요청 시 중복 발송/저장을 막는 데 '
    '사용한다. NULL이면(기존 클라이언트) idempotency를 보장하지 않는다.';
