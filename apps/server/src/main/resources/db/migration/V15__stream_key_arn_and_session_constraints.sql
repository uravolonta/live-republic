-- PR #21 리뷰 4차: Stream Key 폐기를 위해 ARN을 보존하고, Session 이력의 정합을 DB에서 방어한다.
ALTER TABLE live ADD COLUMN ivs_stream_key_arn VARCHAR(200);

-- 같은 Stream Session이 중복 기록되지 않게 한다.
ALTER TABLE live_stream_session ADD CONSTRAINT uq_lss_live_stream UNIQUE (live_id, ivs_stream_id);

-- Live당 열린(종료되지 않은) Session은 하나만 허용한다.
CREATE UNIQUE INDEX uq_lss_open_per_live ON live_stream_session (live_id) WHERE ended_at IS NULL;
