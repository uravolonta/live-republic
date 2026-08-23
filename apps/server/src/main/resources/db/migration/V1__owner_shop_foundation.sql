-- Issue #2: Owner 가입과 Shop·계좌·배송정보의 기반 Schema.
-- Tenant는 데이터 격리 경계, Shop은 판매 공간이다. 이 Slice에서는 1 Tenant = 1 Shop.

CREATE TABLE tenant (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE membership (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user (id),
    tenant_id  BIGINT NOT NULL REFERENCES tenant (id),
    role       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_membership_tenant ON membership (tenant_id);

CREATE TABLE shop (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT NOT NULL UNIQUE REFERENCES tenant (id),
    name                VARCHAR(100) NOT NULL,
    -- 직접입금 계좌 정보 (Customer에게 안내)
    bank_name           VARCHAR(50),
    bank_account_number VARCHAR(50),
    bank_account_holder VARCHAR(50),
    -- 기본 배송정보
    courier_name        VARCHAR(50),
    base_shipping_fee   INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
