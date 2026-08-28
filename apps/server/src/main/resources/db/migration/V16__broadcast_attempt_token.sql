-- PR #21 5차 리뷰: 계정이 아니라 "단말"을 식별하는 송출 임대 토큰.
-- start가 임의 토큰을 발급해 해시만 저장하고, Stream Key·방송 조작(confirm·전환)은
-- 토큰을 제시한 단말에만 허용한다. start 재호출은 토큰을 회전시켜 이전 단말을 무효화한다.
ALTER TABLE live ADD COLUMN broadcast_token_hash VARCHAR(64);

-- 기존 데이터 보정: V15 이전에 발급된 Stream Key는 ARN이 없어 폐기할 수 없다.
-- DB에서 제거해 재시작 시 새 Key를 발급하게 한다. AWS에 남은 잔여 Key는
-- Preview 전용 자원이며 Channel 정리(#23)에서 함께 폐기한다.
UPDATE live SET ivs_stream_key = NULL
WHERE ivs_stream_key IS NOT NULL AND ivs_stream_key_arn IS NULL;
