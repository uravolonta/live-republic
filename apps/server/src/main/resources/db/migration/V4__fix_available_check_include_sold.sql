-- VS-001 §4의 Available = On Hand − Reserved − Sold 공식에 맞춰
-- 음수 방지 제약에 Sold를 포함한다. (V3는 이미 적용되어 수정 불가)
ALTER TABLE sku DROP CONSTRAINT chk_sku_available;
ALTER TABLE sku ADD CONSTRAINT chk_sku_available CHECK (on_hand >= reserved + sold);
