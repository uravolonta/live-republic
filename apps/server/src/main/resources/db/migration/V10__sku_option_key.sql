-- 표시 이름(option_label, 옵션 값만)으로 SKU 동일성을 판단하면 그룹명이 바뀌어도
-- 값이 같은 조합(색상=빨강 → 소재=빨강)에 재고·이력이 잘못 이전된다.
-- 그룹명을 포함한 안정적 조합 키를 SKU에 저장해 식별한다. (예: "색상=빨강 / 사이즈=M", 옵션 없음은 "기본")
ALTER TABLE sku ADD COLUMN option_key VARCHAR(500);

-- 현재 구조 매핑이 있는 SKU는 그룹명 포함 키로 채운다.
UPDATE sku s
SET option_key = sub.key
FROM (
    SELECT so.sku_id, string_agg(g.name || '=' || o.name, ' / ' ORDER BY g.position) AS key
    FROM sku_option so
    JOIN product_option o ON o.id = so.option_id
    JOIN product_option_group g ON g.id = o.option_group_id
    GROUP BY so.sku_id
) sub
WHERE s.id = sub.sku_id;

-- 매핑이 없는 SKU(옵션 없는 "기본", 또는 과거 보관된 SKU)는 표시 이름으로 대체한다.
UPDATE sku SET option_key = option_label WHERE option_key IS NULL;

ALTER TABLE sku ALTER COLUMN option_key SET NOT NULL;
