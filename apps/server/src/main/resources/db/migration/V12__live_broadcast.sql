-- Issue #5: 실제 방송 시작·종료. 예정 시각과 실제 방송 시각을 구분하고,
-- 시작 요청자와 IVS Channel 연결을 보존한다.
ALTER TABLE live ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE live ADD COLUMN ended_at TIMESTAMPTZ;
ALTER TABLE live ADD COLUMN started_by_user_id BIGINT REFERENCES app_user (id);

-- IVS Channel 연결 (Live당 Channel 1개, 시작 시 생성)
ALTER TABLE live ADD COLUMN ivs_channel_arn VARCHAR(200);
ALTER TABLE live ADD COLUMN ivs_ingest_endpoint VARCHAR(300);
ALTER TABLE live ADD COLUMN ivs_stream_key VARCHAR(300);
ALTER TABLE live ADD COLUMN ivs_playback_url VARCHAR(500);

-- 방송 중 현재 판매 상품 (Streamer가 전환)
ALTER TABLE live ADD COLUMN current_live_product_id BIGINT REFERENCES live_product (id);

-- 정책(2026-08-24, Issue #5 기록): 방송 중(LIVE) Live는 Shop당 최대 1개.
CREATE UNIQUE INDEX uq_live_one_active_per_shop ON live (shop_id) WHERE status = 'LIVE';
