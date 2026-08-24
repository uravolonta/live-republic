-- PR #21 리뷰: 재연결마다 새 IVS Stream Session이 생기므로 Live당 1:N 이력으로 보존한다.
-- (VS-001: Live와 IVS Stream Session의 연결·사용량 근거 보존)
CREATE TABLE live_stream_session (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    live_id         BIGINT NOT NULL REFERENCES live (id),
    ivs_channel_arn VARCHAR(200) NOT NULL,
    ivs_stream_id   VARCHAR(100) NOT NULL,
    -- 연결이 확인된 시각과 종료(연결 해제·방송 종료)가 확인된 시각
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_live_stream_session_live ON live_stream_session (live_id);
