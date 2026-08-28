-- PR #21 리뷰: 실제 IVS 연결이 확인된 뒤에만 방송 중(LIVE)으로 확정한다.
-- SCHEDULED → STARTING(시작 요청·송출 자격 발급) → LIVE(SDK 연결 확인) → ENDED.
-- STARTING에서 종료하면 SCHEDULED로 되돌아간다(시작 취소).

-- IVS Stream Session 식별자 — 연결 확인 시점에 IVS에서 조회해 보존한다.
ALTER TABLE live ADD COLUMN ivs_stream_session_id VARCHAR(100);

-- 동시 방송 제한은 STARTING도 포함한다 (시작 슬롯 선점).
DROP INDEX uq_live_one_active_per_shop;
CREATE UNIQUE INDEX uq_live_one_active_per_shop ON live (shop_id) WHERE status IN ('STARTING', 'LIVE');
