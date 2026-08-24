-- P2: 이름의 '/'·'=' 금지 대신 키를 이스케이프 인코딩으로 만들어 충돌을 없앤다.
--     ('\' -> '\\', '=' -> '\=', '/' -> '\/') 기존 이름은 그대로 허용된다.
-- 구조 매핑이 있는 SKU의 키를 이스케이프 인코딩으로 다시 채운다.
UPDATE sku s
SET option_key = sub.key
FROM (
    SELECT so.sku_id, string_agg(
        replace(replace(replace(g.name, '\', '\\'), '=', '\='), '/', '\/')
        || '=' ||
        replace(replace(replace(o.name, '\', '\\'), '=', '\='), '/', '\/'),
        ' / ' ORDER BY g.position) AS key
    FROM sku_option so
    JOIN product_option o ON o.id = so.option_id
    JOIN product_option_group g ON g.id = o.option_group_id
    GROUP BY so.sku_id
) sub
WHERE s.id = sub.sku_id;

-- 매핑이 없는 과거 보관 SKU의 fallback 키가 중복이면 id를 붙여 유일화한다.
UPDATE sku s
SET option_key = s.option_key || ' #' || s.id
WHERE EXISTS (
    SELECT 1 FROM sku d
    WHERE d.product_id = s.product_id AND d.option_key = s.option_key AND d.id < s.id
);

-- P1: 동시 구조 변경이 같은 조합의 SKU를 중복 생성하지 못하도록 DB에서 보장한다.
CREATE UNIQUE INDEX uq_sku_product_option_key ON sku (product_id, option_key);
