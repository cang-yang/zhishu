package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(String eventId);

    Optional<OutboxEvent> findByTaskId(String taskId);

    @Query(value = """
            SELECT event_id
              FROM outbox_event
             WHERE (status = 'NEW'
                    AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP))
                OR (status = 'SENDING'
                    AND updated_at <= TIMESTAMPADD(SECOND, -1 * :staleSeconds, CURRENT_TIMESTAMP))
             ORDER BY id
             LIMIT :batchSize
            """, nativeQuery = true)
    List<String> findDispatchCandidates(
            @Param("batchSize") int batchSize,
            @Param("staleSeconds") int staleSeconds);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'SENDING',
                   updated_at = CURRENT_TIMESTAMP
             WHERE event_id = :eventId
               AND ((status = 'NEW'
                     AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP))
                    OR (status = 'SENDING'
                        AND updated_at <= TIMESTAMPADD(SECOND, -1 * :staleSeconds, CURRENT_TIMESTAMP)))
            """, nativeQuery = true)
    int claim(@Param("eventId") String eventId, @Param("staleSeconds") int staleSeconds);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'PUBLISHED',
                   last_error = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE event_id = :eventId
               AND status = 'SENDING'
            """, nativeQuery = true)
    int markPublished(@Param("eventId") String eventId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'NEW',
                   retry_count = retry_count + 1,
                   next_retry_at = TIMESTAMPADD(SECOND, :retryDelaySeconds, CURRENT_TIMESTAMP),
                   last_error = :errorMessage,
                   updated_at = CURRENT_TIMESTAMP
             WHERE event_id = :eventId
               AND status = 'SENDING'
               AND retry_count + 1 < :maxAttempts
            """, nativeQuery = true)
    int rescheduleIfRetryable(
            @Param("eventId") String eventId,
            @Param("errorMessage") String errorMessage,
            @Param("retryDelaySeconds") int retryDelaySeconds,
            @Param("maxAttempts") int maxAttempts);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'DEAD',
                   retry_count = retry_count + 1,
                   next_retry_at = NULL,
                   last_error = :errorMessage,
                   updated_at = CURRENT_TIMESTAMP
             WHERE event_id = :eventId
               AND status = 'SENDING'
               AND retry_count + 1 >= :maxAttempts
            """, nativeQuery = true)
    int markDeadIfExhausted(
            @Param("eventId") String eventId,
            @Param("errorMessage") String errorMessage,
            @Param("maxAttempts") int maxAttempts);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'NEW',
                   retry_count = 0,
                   next_retry_at = NULL,
                   last_error = :reason,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task_id = :taskId
            """, nativeQuery = true)
    int resetForTask(
            @Param("taskId") String taskId,
            @Param("reason") String reason);
}
