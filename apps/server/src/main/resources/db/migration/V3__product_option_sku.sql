-- Issue #3: 상품, Option과 SKU·재고의 기반 Schema.
-- SKU는 Option 조합당 하나이며 재고(On Hand/Reserved/Sold)의 단위다.

CREATE TABLE product (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenant (id),
    name        VARCHAR(200) NOT NULL,
    price       INTEGER NOT NULL CHECK (price >= 0),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_tenant ON product (tenant_id);

CREATE TABLE product_option_group (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product (id),
    name       VARCHAR(50) NOT NULL,
    position   INT NOT NULL
);

CREATE INDEX idx_option_group_product ON product_option_group (product_id);

CREATE TABLE product_option (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    option_group_id BIGINT NOT NULL REFERENCES product_option_group (id),
    name            VARCHAR(50) NOT NULL,
    position        INT NOT NULL
);

CREATE INDEX idx_option_group ON product_option (option_group_id);

CREATE TABLE sku (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id   BIGINT NOT NULL REFERENCES product (id),
    -- 표시용 Option 조합 이름 (예: "빨강 / L", Option이 없으면 "기본")
    option_label VARCHAR(200) NOT NULL,
    on_hand      INT NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved     INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    sold         INT NOT NULL DEFAULT 0 CHECK (sold >= 0),
    -- Available(= on_hand - reserved)이 음수가 되지 않게 DB에서 보장한다.
    CONSTRAINT chk_sku_available CHECK (on_hand >= reserved),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sku_product ON sku (product_id);

-- SKU가 어떤 Option 조합인지의 정규 관계 (표시는 option_label 사용)
CREATE TABLE sku_option (
    sku_id    BIGINT NOT NULL REFERENCES sku (id),
    option_id BIGINT NOT NULL REFERENCES product_option (id),
    PRIMARY KEY (sku_id, option_id)
);
