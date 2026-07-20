package com.canggo.zhishu.consumer;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.model.OutboxStatus;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.model.ProcessingTaskStatus;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentProcessingDltConsumerTest {

    @Autowired
    private DocumentProcessingTaskRepository taskRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;

    private DocumentProcessingDltConsumer consumer;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        consumer = new DocumentProcessingDltConsumer(taskRepository);
    }

    @Test
    void dltOnlyFailsReleasedRetryWaitTaskAndRecordsOrigin() {
        DocumentProcessingTask retryWait = saveTask(ProcessingTaskStatus.RETRY_WAIT, null);
        DocumentProcessingRequested requested = requested(retryWait);
        saveOutbox(requested, OutboxStatus.PUBLISHED);

        consumer.processDlt(
                requested,
                "document-processing-v2",
                2,
                41L,
                "embedding provider unavailable");

        DocumentProcessingTask failed = taskRepository.findByTaskId(retryWait.getTaskId()).orElseThrow();
        assertEquals(ProcessingTaskStatus.FAILED, failed.getStatus());
        assertNull(failed.getExecutionToken());
        assertTrue(failed.getErrorMessage().contains("topic=document-processing-v2"));
        assertTrue(failed.getErrorMessage().contains("partition=2"));
        assertTrue(failed.getErrorMessage().contains("offset=41"));
        assertTrue(failed.getErrorMessage().contains("embedding provider unavailable"));
    }

    @Test
    void matchingPublishedDltCanFailPendingClaimContextFailure() {
        DocumentProcessingTask pending = saveTask(ProcessingTaskStatus.PENDING, null);
        DocumentProcessingRequested requested = requested(pending);
        saveOutbox(requested, OutboxStatus.PUBLISHED);

        consumer.processDlt(requested, "document-processing-v2", 0, 12L, "claim context failed");

        assertEquals(ProcessingTaskStatus.FAILED, reload(pending).getStatus());
    }

    @Test
    void staleDltCannotOverwritePendingProcessingOrCompletedTask() {
        DocumentProcessingTask pending = saveTask(ProcessingTaskStatus.PENDING, null);
        DocumentProcessingTask processing = saveTask(ProcessingTaskStatus.PROCESSING, "live-token");
        DocumentProcessingTask completed = saveTask(ProcessingTaskStatus.COMPLETED, null);
        DocumentProcessingTask claimedRetry = saveTask(ProcessingTaskStatus.RETRY_WAIT, "new-token");

        DocumentProcessingRequested stalePending = requested(pending);
        saveOutbox(stalePending, OutboxStatus.NEW);
        consumer.processDlt(stalePending, "document-processing-v2", 0, 9L, "late DLT");
        for (DocumentProcessingTask task : new DocumentProcessingTask[]{processing, completed, claimedRetry}) {
            consumer.processDlt(requested(task), "document-processing-v2", 0, 9L, "late DLT");
        }

        assertEquals(ProcessingTaskStatus.PENDING, reload(pending).getStatus());
        assertEquals(ProcessingTaskStatus.PROCESSING, reload(processing).getStatus());
        assertEquals(ProcessingTaskStatus.COMPLETED, reload(completed).getStatus());
        assertEquals(ProcessingTaskStatus.RETRY_WAIT, reload(claimedRetry).getStatus());
        assertEquals("new-token", reload(claimedRetry).getExecutionToken());
    }

    private DocumentProcessingTask saveTask(ProcessingTaskStatus status, String token) {
        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setFileUploadId(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        task.setProcessingVersion(1);
        task.setStatus(status);
        task.setCurrentStage(ProcessingStage.INDEX);
        task.setRetryCount(4);
        task.setSourceObjectKey("merged/key");
        task.setExecutionToken(token);
        return taskRepository.saveAndFlush(task);
    }

    private DocumentProcessingRequested requested(DocumentProcessingTask task) {
        return new DocumentProcessingRequested(
                UUID.randomUUID().toString(),
                task.getTaskId(),
                task.getFileUploadId(),
                task.getProcessingVersion(),
                task.getSourceObjectKey());
    }

    private void saveOutbox(DocumentProcessingRequested requested, OutboxStatus status) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(requested.eventId());
        outbox.setTaskId(requested.taskId());
        outbox.setPayload("{}");
        outbox.setStatus(status);
        outbox.setRetryCount(0);
        outboxRepository.saveAndFlush(outbox);
    }

    private DocumentProcessingTask reload(DocumentProcessingTask task) {
        return taskRepository.findByTaskId(task.getTaskId()).orElseThrow();
    }
}
