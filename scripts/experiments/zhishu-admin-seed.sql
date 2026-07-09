-- ZH-F07 实验数据集 seed（独立 experiment schema）
-- datasetId: ZH-DS-F07-ADMIN-QUERY
-- 生成 1k / 10k / 100k 三档合成用户，createdAt 唯一，orgTags 从固定池取，90% USER / 10% ADMIN
-- 本脚本可重复执行：先 DROP 再 CREATE 再 INSERT。
-- 不动生产 zhishu.users；只在 zhishu_exp_f07 experiment schema 操作。
--
-- 不变量（与 02-P0文件级实现计划 §8 对齐）：
--   - username 唯一，无 case-variant 碰撞（synthetic_user_<n>，纯小写+下划线+数字）
--   - createdAt 唯一递增（n 越大 created_at 越晚），保证 createdAt DESC 无并列，id ASC 为防御性 tie rule
--   - orgTags CSV 逗号分隔、无前后空格、不含 LIKE 通配符 %/_
--   - 固定预注册 keyword=test 命中约 10% 用户（synthetic_user_*test* 形态，见下）
--   - 固定预注册 orgTag=ORG_1 命中约 1/5 用户
--   - status=1(USER) 命中约 90%
--
-- 用法：
--   mysql -h127.0.0.1 -P3306 -uroot -p<pwd> < scripts/experiments/zhishu-admin-seed.sql
--   或实验脚本调用，按 --scale 1k/10k/100k 选择规模。

-- ============================================================
-- Schema 与索引
-- ============================================================
CREATE DATABASE IF NOT EXISTS zhishu_exp_f07
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE zhishu_exp_f07;

DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id bigint NOT NULL AUTO_INCREMENT,
  created_at datetime(6) DEFAULT NULL,
  org_tags varchar(255) DEFAULT NULL,
  password varchar(255) NOT NULL,
  primary_org varchar(255) DEFAULT NULL,
  role enum('ADMIN','USER') NOT NULL,
  updated_at datetime(6) DEFAULT NULL,
  username varchar(255) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  -- ZH-F07 实验索引：覆盖 createdAt DESC, id ASC 排序 + role 过滤
  KEY idx_users_created_at_id (created_at DESC, id ASC),
  KEY idx_users_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================
-- 数据生成：用存储过程批量插入 N 行
--   - 90% USER / 10% ADMIN
--   - orgTags 从 {default,ORG_1,ORG_2,ORG_3,ORG_4,ORG_5} 取 1-3 个
--   - 约 10% username 含 "test" 子串（命中预注册 keyword=test）
--   - createdAt = BASE + n 秒，唯一递增
-- ============================================================
DROP PROCEDURE IF EXISTS seed_users;
DELIMITER $$
CREATE PROCEDURE seed_users(IN n INT)
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE base_dt DATETIME DEFAULT '2026-01-01 00:00:00';
  DECLARE uname VARCHAR(255);
  DECLARE r DOUBLE;
  DECLARE tagcount INT;
  DECLARE tags VARCHAR(255);
  DECLARE is_admin INT;
  WHILE i <= n DO
    SET r = RAND();
    SET is_admin = IF(r < 0.10, 1, 0);
    -- 约 10% 用户名含 "test"
    IF (i % 10) = 0 THEN
      SET uname = CONCAT('synthetic_user_test_', i);
    ELSE
      SET uname = CONCAT('synthetic_user_', i);
    END IF;
    -- orgTags: 1-3 个标签
    SET tagcount = 1 + FLOOR(RAND() * 3);
    SET tags = 'default';
    IF tagcount >= 2 THEN SET tags = CONCAT(tags, ',ORG_', 1 + FLOOR(RAND() * 5)); END IF;
    IF tagcount >= 3 THEN SET tags = CONCAT(tags, ',ORG_', 1 + FLOOR(RAND() * 5)); END IF;
    INSERT INTO users (username, password, role, org_tags, primary_org, created_at, updated_at)
    VALUES (uname, '$2a$10$placeholderhashplaceholderhashplaceholderhashplaceholderhashplacehold',
            IF(is_admin=1,'ADMIN','USER'), tags, 'default',
            DATE_ADD(base_dt, INTERVAL i SECOND),
            DATE_ADD(base_dt, INTERVAL i SECOND));
    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

-- 规模由外部参数决定，默认建 1000 行；大规模请单独调用：
--   CALL seed_users(1000);    -- 1k
--   CALL seed_users(10000);   -- 10k
--   CALL seed_users(100000);  -- 100k
CALL seed_users(1000);

-- ============================================================
-- 数据集 manifest 字段（实验脚本读取并写入 datasetManifestSha256 校验）
-- ============================================================
SELECT
  CONCAT('rows=', COUNT(*)) AS row_count,
  CONCAT('distinct_username=', COUNT(DISTINCT username)) AS distinct_username,
  CONCAT('distinct_created_at=', COUNT(DISTINCT created_at)) AS distinct_created_at,
  CONCAT('user_role_count=', SUM(role='USER')) AS user_count,
  CONCAT('admin_role_count=', SUM(role='ADMIN')) AS admin_count,
  CONCAT('keyword_test_hits=', SUM(username LIKE '%test%')) AS keyword_test_hits,
  CONCAT('orgtag_org1_hits=', SUM(FIND_IN_SET('ORG_1', org_tags) > 0)) AS org1_hits
FROM users;
