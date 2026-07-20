package com.canggo.zhishu.service;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.model.OutboxStatus;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.model.ProcessingTaskStatus;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({OutboxDispatcher.class, OutboxDispatcherTest.Config.class})
@TestPropertySource(properties = {
        "spring.kafka.topic.document-processing-v2=document-processing-v2",
        "document-processing.outbox.batch-size=20",
        "document-processing.outbox.stale-seconds=60",
        "document-processing.outbox.send-timeout-seconds=20",
        "document-processing.outbox.retry-delay-seconds=1",
        "document-processing.outbox.max-attempts=5"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxDispatcherTest {

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private DocumentProcessingTaskRepository taskRepository;
    @Autowired
    private FileUploadRepository fileUploadRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void onlyOneDispatcherCanClaimTheSameEvent() {
        Seed seed = seed(0, OutboxStatus.NEW, null);

        assertEquals(1, outboxRepository.claim(seed.eventId(), 60));
        assertEquals(0, outboxRepository.claim(seed.eventId(), 60));
    }

    @Test
    void futureRetryIsNotSelectedAndPublishedCannotBeOverwrittenByFailureCallback() {
        Seed seed = seed(0, OutboxStatus.NEW, LocalDateTime.now().plusMinutes(5));
        assertTrue(outboxRepository.findDispatchCandidates(20, 60).isEmpty());

        jdbcTemplate.update("update outbox_event set next_retry_at = null where event_id = ?", seed.eventId());
        assertEquals(1, outboxRepository.claim(seed.eventId(), 60));
        assertEquals(1, outboxRepository.markPublished(seed.eventId()));
        assertEquals(0, outboxRepository.rescheduleIfRetryable(seed.eventId(), "late failure", 1, 5));
        assertEquals(0, outboxRepository.markDeadIfExhausted(seed.eventId(), "late failure", 5));
        assertEquals(OutboxStatus.PUBLISHED,
                outboxRepository.findByEventId(seed.eventId()).orElseThrow().getStatus());
    }

    @Test
    void successfulKafkaTransactionMarksEventPublished() {
        Seed seed = seed(0, OutboxStatus.NEW, null);
        KafkaOperations<String, Object> operations = successfulKafkaTransaction();

        dispatcher.dispatchBatch();

        assertEquals(OutboxStatus.PUBLISHED,
                outboxRepository.findByEventId(seed.eventId()).orElseThrow().getStatus());
        verify(kafkaTemplate).executeInTransaction(any());
        verify(operations).send(
                eq("document-processing-v2"),
                eq(String.valueOf(seed.fileUploadId())),
                eq(seed.requested()));
    }

    @Test
    void transientKafkaFailureReschedulesWithoutMarkingPublished() {
        Seed seed = seed(0, OutboxStatus.NEW, null);
        when(kafkaTemplate.executeInTransaction(any()))
                .thenThrow(new RuntimeException("broker temporarily unavailable"));

        dispatcher.dispatchBatch();

        OutboxEvent retryable = outboxRepository.findByEventId(seed.eventId()).orElseThrow();
        assertEquals(OutboxStatus.NEW, retryable.getStatus());
        assertEquals(1, retryable.getRetryCount());
        assertTrue(retryable.getNextRetryAt() != null);
        assertEquals(ProcessingTaskStatus.PENDING,
                taskRepository.findByTaskId(seed.taskId()).orElseThrow().getStatus());
    }

    @Test
    void kafkaAckWithoutPublishedWriteIsReclaimedAndSentAgain() {
        Seed seed = seed(0, OutboxStatus.SENDING, null);
        jdbcTemplate.update(
                "update outbox_event set updated_at = TIMESTAMPADD(SECOND, -61, CURRENT_TIMESTAMP) where event_id = ?",
                seed.eventId());
        KafkaOperations<String, Object> operations = successfulKafkaTransaction();

        dispatcher.dispatchBatch();

        assertEquals(OutboxStatus.PUBLISHED,
                outboxRepository.findByEventId(seed.eventId()).orElseThrow().getStatus());
        verify(kafkaTemplate, times(1)).executeInTransaction(any());
        verify(operations).send(
                eq("document-processing-v2"),
                eq(String.valueOf(seed.fileUploadId())),
                eq(seed.requested()));
    }

    @Test
    void exhaustedDeliveryMarksOutboxDeadAndOnlyPendingTaskFailed() {
        Seed seed = seed(4, OutboxStatus.NEW, null);
        when(kafkaTemplate.executeInTransaction(any())).thenThrow(new RuntimeException("broker unavailable"));

        dispatcher.dispatchBatch();

        OutboxEvent dead = outboxRepository.findByEventId(seed.eventId()).orElseThrow();
        DocumentProcessingTask failed = taskRepository.findByTaskId(seed.taskId()).orElseThrow();
        assertEquals(OutboxStatus.DEAD, dead.getStatus());
        assertEquals(5, dead.getRetryCount());
        assertEquals(ProcessingTaskStatus.FAILED, failed.getStatus());
        assertEquals(ProcessingStage.DISPATCH, failed.getCurrentStage());
    }

    private Seed seed(int retryCount, OutboxStatus status, LocalDateTime nextRetryAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        FileUpload file = new FileUpload();
        file.setFileMd5(suffix);
        file.setFileName(suffix + ".pdf");
        file.setTotalSize(100L);
        file.setStatus(1);
        file.setUserId("owner");
        file.setLatestProcessingVersion(1);
        file = fileUploadRepository.saveAndFlush(file);

        String taskId = UUID.randomUUID().toString();
        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(taskId);
        task.setFileUploadId(file.getId());
        task.setProcessingVersion(1);
        task.setStatus(ProcessingTaskStatus.PENDING);
        task.setCurrentStage(ProcessingStage.DISPATCH);
        task.setRetryCount(0);
        task.setSourceObjectKey("merged/" + suffix);
        taskRepository.saveAndFlush(task);

        String eventId = UUID.randomUUID().toString();
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                eventId, taskId, file.getId(), 1, task.getSourceObjectKey());
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setTaskId(taskId);
        try {
            event.setPayload(objectMapper.writeValueAsString(requested));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        event.setStatus(status);
        event.setRetryCount(retryCount);
        event.setNextRetryAt(nextRetryAt);
        outboxRepository.saveAndFlush(event);
        return new Seed(eventId, taskId, file.getId(), requested);
    }

    @SuppressWarnings("unchecked")
    private KafkaOperations<String, Object> successfulKafkaTransaction() {
        KafkaOperations<String, Object> operations = mock(KafkaOperations.class);
        SendResult<String, Object> result = mock(SendResult.class);
        when(operations.send(any(String.class), any(String.class), any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<String, Object, Object> callback = invocation.getArgument(0);
            return callback.doInOperations(operations);
        });
        return operations;
    }

    private record Seed(
            String eventId,
            String taskId,
            Long fileUploadId,
            DocumentProcessingRequested requested) {
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
