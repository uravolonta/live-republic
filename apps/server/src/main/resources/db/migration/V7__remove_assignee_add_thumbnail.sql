-- 결정(2026-08-24): 예정 Live의 담당자 지정을 제거한다. 예정 Live는 사전 예고이며
-- 실제 진행자는 방송 시작 시점(Issue #5)에 started_by로 기록한다.
ALTER TABLE live DROP COLUMN streamer_user_id;

-- Live 썸네일은 URL만 저장한다. 이미지 업로드 인프라는 별도 티켓에서 결정한다.
ALTER TABLE live ADD COLUMN thumbnail_url VARCHAR(500);
