-- Issue #4: 예정 Live, Live 판매 상품, Streamer 서브계정의 최초 비밀번호 변경 강제.

-- Streamer 서브계정은 임시 비밀번호로 생성되며 최초 로그인 후 반드시 변경해야 한다.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE live (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id            BIGINT NOT NULL REFERENCES shop (id),
    title              VARCHAR(200) NOT NULL,
    -- SCHEDULED, CANCELLED (LIVE·ENDED는 Issue #5에서 추가)
    status             VARCHAR(20) NOT NULL,
    -- 예정 시각(사전 예고). 실제 시작·종료 시각(started_at·ended_at)은 Issue #5에서 추가한다.
    scheduled_start_at TIMESTAMPTZ NOT NULL,
    -- Live 담당자: 같은 Shop의 활성 OWNER 또는 STREAMER. 연결 전에는 NULL.
    streamer_user_id   BIGINT REFERENCES app_user (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_live_shop ON live (shop_id);

CREATE TABLE live_product (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    live_id    BIGINT NOT NULL REFERENCES live (id),
    product_id BIGINT NOT NULL REFERENCES product (id),
    position   INT NOT NULL,
    -- 같은 Live에 같은 상품·같은 표시 순서는 하나만 허용한다.
    CONSTRAINT uq_live_product UNIQUE (live_id, product_id),
    CONSTRAINT uq_live_product_position UNIQUE (live_id, position)
);
