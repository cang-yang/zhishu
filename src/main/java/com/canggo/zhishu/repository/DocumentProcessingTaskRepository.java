package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.DocumentProcessingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

public interface DocumentProcessingTaskRepository extends JpaRepository<DocumentProcessingTask, Long> {

    Optional<DocumentProcessingTask> findByTaskId(String taskId);

    Optional<DocumentProcessingTask> findByFileUploadIdAndProcessingVersion(
            Long fileUploadId,
            Integer processingVersion);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'PROCESSING',
                   execution_token = :executionToken,
                   lease_expire_at = TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP),
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status IN ('PENDING', 'RETRY_WAIT')
               AND execution_token IS NULL
            """, nativeQuery = true)
    int claim(
            @Param("taskId") String taskId,
            @Param("executionToken") String executionToken,
            @Param("leaseSeconds") int leaseSeconds);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'PROCESSING',
                   execution_token = :executionToken,
                   lease_expire_at = TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP),
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND file_upload_id = :fileUploadId
               AND processing_version = :processingVersion
               AND source_object_key = :sourceObjectKey
               AND status IN ('PENDING', 'RETRY_WAIT')
               AND execution_token IS NULL
            """, nativeQuery = true)
    int claimRequested(
            @Param("taskId") String taskId,
            @Param("fileUploadId") Long fileUploadId,
            @Param("processingVersion") int processingVersion,
            @Param("sourceObjectKey") String sourceObjectKey,
            @Param("executionToken") String executionToken,
            @Param("leaseSeconds") int leaseSeconds);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET current_stage = :stage,
                   lease_expire_at = TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP),
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PROCESSING'
               AND execution_token = :executionToken
            """, nativeQuery = true)
    int updateStage(
            @Param("taskId") String taskId,
            @Param("executionToken") String executionToken,
            @Param("stage") String stage,
            @Param("leaseSeconds") int leaseSeconds);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'COMPLETED',
                   current_stage = 'ACTIVATE',
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   error_message = NULL,
                   completed_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PROCESSING'
               AND execution_token = :executionToken
            """, nativeQuery = true)
    int complete(
            @Param("taskId") String taskId,
            @Param("executionToken") String executionToken);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'RETRY_WAIT',
                   current_stage = :stage,
                   retry_count = retry_count + 1,
                   error_message = :errorMessage,
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PROCESSING'
               AND execution_token = :executionToken
            """, nativeQuery = true)
    int retryAfterFailure(
            @Param("taskId") String taskId,
            @Param("executionToken") String executionToken,
            @Param("stage") String stage,
            @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'FAILED',
                   current_stage = 'DISPATCH',
                   error_message = :errorMessage,
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PENDING'
            """, nativeQuery = true)
    int failPendingDispatch(
            @Param("taskId") String taskId,
            @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'FAILED',
                   error_message = :errorMessage,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'RETRY_WAIT'
               AND execution_token IS NULL
            """, nativeQuery = true)
    int failReleasedRetryWait(
            @Param("taskId") String taskId,
            @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'FAILED',
                   error_message = :errorMessage,
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status IN ('PENDING', 'RETRY_WAIT')
               AND execution_token IS NULL
               AND EXISTS (
                   SELECT 1
                     FROM outbox_event published_event
                    WHERE published_event.task_id = document_processing_task.task_id
                      AND published_event.event_id = :eventId
                      AND published_event.status = 'PUBLISHED'
               )
            """, nativeQuery = true)
    int failPublishedUnownedDelivery(
            @Param("taskId") String taskId,
            @Param("eventId") String eventId,
            @Param("errorMessage") String errorMessage);

    @Query(value = """
            SELECT task_id
              FROM document_processing_task
             WHERE status = 'PROCESSING'
               AND lease_expire_at IS NOT NULL
               AND lease_expire_at <= CURRENT_TIMESTAMP
             ORDER BY id
             LIMIT :batchSize
            """, nativeQuery = true)
    List<String> findExpiredTaskIds(@Param("batchSize") int batchSize);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'RETRY_WAIT',
                   retry_count = retry_count + 1,
                   error_message = :errorMessage,
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PROCESSING'
               AND lease_expire_at IS NOT NULL
               AND lease_expire_at <= CURRENT_TIMESTAMP
               AND retry_count + 1 < :maxRecoveries
            """, nativeQuery = true)
    int recoverExpiredToRetry(
            @Param("taskId") String taskId,
            @Param("errorMessage") String errorMessage,
            @Param("maxRecoveries") int maxRecoveries);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document_processing_task
               SET status = 'FAILED',
                   retry_count = retry_count + 1,
                   error_message = :errorMessage,
                   execution_token = NULL,
                   lease_expire_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
               AND status = 'PROCESSING'
               AND lease_expire_at IS NOT NULL
               AND lease_expire_at <= CURRENT_TIMESTAMP
               AND retry_count + 1 >= :maxRecoveries
            """, nativeQuery = true)
    int failExpired(
            @Param("taskId") String taskId,
            @Param("errorMessage") String errorMessage,
            @Param("maxRecoveries") int maxRecoveries);
}
