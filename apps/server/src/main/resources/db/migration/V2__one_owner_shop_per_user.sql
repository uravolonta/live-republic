-- 동시 요청이 선조회를 함께 통과해도 계정 하나에 Owner Shop이 하나만 생기도록
-- DB에서 규칙을 보장한다. STREAMER 등 다른 Role은 제한하지 않는다.
CREATE UNIQUE INDEX uq_membership_owner_per_user
    ON membership (user_id)
    WHERE role = 'OWNER';
