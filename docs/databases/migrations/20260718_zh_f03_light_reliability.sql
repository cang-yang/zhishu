-- ZH-F03 轻量可靠闭环：additive migration for MySQL 8.
-- 执行前请备份数据库。应用回滚时保留这些列和表，不做破坏性 down migration。

-- 1. legacy 信任迁移前置审计：这些行不能进入新 versioned 路径的公开验收结论。
SELECT f.id,
       f.file_md5,
       f.user_id,
       COUNT(v.vector_id) AS mysql_chunk_count
  FROM file_upload f
  LEFT JOIN document_vectors v
    ON v.file_md5 = f.file_md5
   AND v.user_id = f.user_id
 WHERE f.status = 1
 GROUP BY f.id, f.file_md5, f.user_id
HAVING COUNT(v.vector_id) = 0;

-- ES 分块数无法在 MySQL migration 内核对；上线前须另行导出同一 (file_md5,user_id)
-- 的 ES count，与上述 MySQL count 比较并保存异常清单。

ALTER TABLE file_upload
    ADD COLUMN latest_processing_version INT NOT NULL DEFAULT 0,
    ADD COLUMN active_processing_version INT NULL;

UPDATE file_upload
   SET latest_processing_version = 0,
       active_processing_version = CASE WHEN status = 1 THEN 0 ELSE NULL END;

CREATE TABLE document_processing_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    file_upload_id BIGINT NOT NULL,
    processing_version INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_stage VARCHAR(24) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000) NULL,
    source_object_key VARCHAR(512) NOT NULL,
    execution_token VARCHAR(36) NULL,
    lease_expire_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_task_id (task_id),
    UNIQUE KEY uk_processing_file_version (file_upload_id, processing_version),
    KEY idx_processing_status_lease (status, lease_expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档异步处理任务';

CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    UNIQUE KEY uk_outbox_task_id (task_id),
    KEY idx_outbox_status_retry_updated (status, next_retry_at, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kafka事务发件箱';

ALTER TABLE document_vectors
    ADD COLUMN file_upload_id BIGINT NULL,
    ADD COLUMN processing_version INT NULL,
    ADD COLUMN content_hash CHAR(64) NULL,
    ADD UNIQUE KEY uk_document_vector_business_key
        (file_upload_id, processing_version, chunk_id);
