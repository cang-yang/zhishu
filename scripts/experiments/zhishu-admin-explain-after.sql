-- ZH-F07 after arm EXPLAIN (与 Hibernate 实际生成 SQL 一致)
-- 投影 6 字段(无 password) + WHERE 下推 + ORDER BY 下推 + LIMIT 下推

SELECT '=== EXPLAIN after data query (LIMIT 20) ===' AS tag;
EXPLAIN SELECT id,username,org_tags,primary_org,role,created_at FROM users
WHERE (username like '%test%' escape '\\') and (find_in_set('ORG_1',org_tags)>0) and (role='USER')
ORDER BY created_at DESC, id LIMIT 20 OFFSET 0;

SELECT '=== EXPLAIN after count query ===' AS tag;
EXPLAIN SELECT count(id) FROM users
WHERE (username like '%test%' escape '\\') and (find_in_set('ORG_1',org_tags)>0) and (role='USER');

SELECT '=== actual totalElements (after count result) ===' AS tag;
SELECT count(id) AS totalElements FROM users
WHERE (username like '%test%' escape '\\') and (find_in_set('ORG_1',org_tags)>0) and (role='USER');

SELECT '=== actual page rows (after LIMIT 20) ===' AS tag;
SELECT id,username,created_at FROM users
WHERE (username like '%test%' escape '\\') and (find_in_set('ORG_1',org_tags)>0) and (role='USER')
ORDER BY created_at DESC, id LIMIT 20 OFFSET 0;
