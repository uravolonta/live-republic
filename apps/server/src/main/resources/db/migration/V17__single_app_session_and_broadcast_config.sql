-- 정책 변경 (2026-08-28 사람 결정, Issue #5 재작업):
-- 1) 방송 앱 로그인은 테넌트당 1개 세션만 허용한다. 세션이 곧 방송 단말의 증명이므로
--    단말 임대 토큰(broadcast_token_hash)은 폐기한다.
-- 2) 방송은 예약 선택 없이 즉시 시작하며, 판매 상품은 Owner Web에서 사전 구성한다
--    (미구성 시 판매 중 전체).

CREATE TABLE app_session (
    tenant_id  bigint PRIMARY KEY REFERENCES tenant (id),
    session_id varchar(120) NOT NULL,
    user_id    bigint       NOT NULL REFERENCES app_user (id),
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE broadcast_product_config (
    shop_id    bigint NOT NULL REFERENCES shop (id),
    product_id bigint NOT NULL REFERENCES product (id),
    position   int    NOT NULL,
    PRIMARY KEY (shop_id, product_id)
);

ALTER TABLE live DROP COLUMN broadcast_token_hash;
