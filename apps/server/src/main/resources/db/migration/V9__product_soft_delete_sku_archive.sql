-- Issue #15: 상품 수정·삭제가 주문·판매 이력에 영향을 주지 않도록
-- 물리 삭제 대신 숨김(soft delete)과 SKU 보관(archive)을 사용한다.
ALTER TABLE product ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE sku ADD COLUMN archived_at TIMESTAMPTZ;
