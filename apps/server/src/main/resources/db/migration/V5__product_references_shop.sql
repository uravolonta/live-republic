-- 결정(2026-08-23): 커머스 도메인 엔티티(상품, Live, 주문 등)는 Shop을 참조한다.
-- Tenant는 계정·Membership·과금의 격리 경계로 남는다. 현재 Tenant:Shop은 1:1이므로 그대로 백필한다.
ALTER TABLE product ADD COLUMN shop_id BIGINT REFERENCES shop (id);

UPDATE product p SET shop_id = s.id FROM shop s WHERE s.tenant_id = p.tenant_id;

ALTER TABLE product ALTER COLUMN shop_id SET NOT NULL;

DROP INDEX idx_product_tenant;
CREATE INDEX idx_product_shop ON product (shop_id);

ALTER TABLE product DROP COLUMN tenant_id;
