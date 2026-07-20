package com.canggo.zhishu.service;

import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.model.OutboxStatus;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.model.ProcessingTaskStatus;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import(DocumentProcessingTaskService.class)
@TestPropertySource(properties = {
        "document-processing.lease-seconds=30",
        "document-processing.max-recoveries=3"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingLeaseRecoveryTest {

    @Autowired
    private DocumentProcessingTaskService taskService;
    @Autowired
    private DocumentProcessingTaskRepository taskRepository;
    @Autowired
    private FileUploadRepository fileUploadRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearPersistentTestRows() {
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        fileUploadRepository.deleteAll();
    }

    @Test
    void expiredProcessingLeaseReturnsToRetryAndResetsUniqueOutbox() {
        Seed seed = seed(1);

        assertEquals(1, taskService.recoverExpiredTasks());

        DocumentProcessingTask recovered = taskRepository.findByTaskId(seed.taskId()).orElseThrow();
        OutboxEvent reset = outboxRepository.findByTaskId(seed.taskId()).orElseThrow();
        assertEquals(ProcessingTaskStatus.RETRY_WAIT, recovered.getStatus());
        assertEquals(2, recovered.getRetryCount());
        assertNull(recovered.getExecutionToken());
        assertNull(recovered.getLeaseExpireAt());
        assertEquals(OutboxStatus.NEW, reset.getStatus());
        assertEquals(0, reset.getRetryCount());
    }

    @Test
    void exhaustedLeaseRecoveryFailsTaskWithoutCreatingAnotherOutbox() {
        Seed seed = seed(2);

        assertEquals(1, taskService.recoverExpiredTasks());

        DocumentProcessingTask failed = taskRepository.findByTaskId(seed.taskId()).orElseThrow();
        assertEquals(ProcessingTaskStatus.FAILED, failed.getStatus());
        assertEquals(3, failed.getRetryCount());
        assertNull(failed.getExecutionToken());
        assertEquals(1, outboxRepository.count());
        assertEquals(OutboxStatus.PUBLISHED,
                outboxRepository.findByTaskId(seed.taskId()).orElseThrow().getStatus());
    }

    @Test
    void missingOutboxFailsOnlyCorruptTaskAndDoesNotBlockHealthyLeaseRecovery() {
        Seed corrupt = seed(1);
        OutboxEvent missing = outboxRepository.findByTaskId(corrupt.taskId()).orElseThrow();
        outboxRepository.delete(missing);
        outboxRepository.flush();
        Seed healthy = seed(1);

        assertEquals(2, taskService.recoverExpiredTasks());

        assertEquals(ProcessingTaskStatus.FAILED,
                taskRepository.findByTaskId(corrupt.taskId()).orElseThrow().getStatus());
        assertEquals(ProcessingTaskStatus.RETRY_WAIT,
                taskRepository.findByTaskId(healthy.taskId()).orElseThrow().getStatus());
        assertEquals(OutboxStatus.NEW,
                outboxRepository.findByTaskId(healthy.taskId()).orElseThrow().getStatus());
    }

    @Test
    void wrongExecutionTokenCannotRenewOrActivateLatestVersion() {
        Seed seed = seed(0);
        DocumentProcessingTaskService.ProcessingClaim wrong = new DocumentProcessingTaskService.ProcessingClaim(
                seed.taskId(), "wrong-token", seed.fileId(), 1, "merged/key", seed.fileMd5(),
                "owner", "org-a", false);

        assertThrows(StaleExecutionException.class,
                () -> taskService.updateStage(wrong, ProcessingStage.INDEX));
        assertThrows(StaleExecutionException.class,
                () -> taskService.complete(wrong,
                        new VectorizationService.VectorizationUsageResult(7, 2, "model")));

        assertNull(fileUploadRepository.findById(seed.fileId()).orElseThrow().getActiveProcessingVersion());
        assertEquals(ProcessingTaskStatus.PROCESSING,
                taskRepository.findByTaskId(seed.taskId()).orElseThrow().getStatus());
    }

    @Test
    void claimContextReadFailureRollsBackProcessingOwnership() {
        Seed seed = seed(0);
        jdbcTemplate.update(
                "update document_processing_task set status='PENDING', execution_token=null, lease_expire_at=null where task_id=?",
                seed.taskId());
        fileUploadRepository.deleteById(seed.fileId());
        fileUploadRepository.flush();
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                "event", seed.taskId(), seed.fileId(), 1, "merged/key");

        assertThrows(IllegalStateException.class, () -> taskService.claim(requested));

        assertEquals(ProcessingTaskStatus.PENDING,
                taskRepository.findByTaskId(seed.taskId()).orElseThrow().getStatus());
        assertNull(taskRepository.findByTaskId(seed.taskId()).orElseThrow().getExecutionToken());
    }

    private Seed seed(int retryCount) {
        String fileMd5 = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        FileUpload file = new FileUpload();
        file.setFileMd5(fileMd5);
        file.setFileName("lease.pdf");
        file.setTotalSize(100L);
        file.setStatus(1);
        file.setUserId("owner");
        file.setOrgTag("org-a");
        file.setLatestProcessingVersion(1);
        file = fileUploadRepository.saveAndFlush(file);

        String taskId = UUID.randomUUID().toString();
        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(taskId);
        task.setFileUploadId(file.getId());
        task.setProcessingVersion(1);
        task.setStatus(ProcessingTaskStatus.PROCESSING);
        task.setCurrentStage(ProcessingStage.INDEX);
        task.setRetryCount(retryCount);
        task.setSourceObjectKey("merged/key");
        task.setExecutionToken("owner-token");
        task.setLeaseExpireAt(LocalDateTime.now().minusMinutes(1));
        taskRepository.saveAndFlush(task);
        jdbcTemplate.update(
                "update document_processing_task set lease_expire_at = TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP) where task_id = ?",
                taskId);

        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setTaskId(taskId);
        outbox.setPayload("{}");
        outbox.setStatus(OutboxStatus.PUBLISHED);
        outbox.setRetryCount(3);
        outboxRepository.saveAndFlush(outbox);
        return new Seed(file.getId(), fileMd5, taskId);
    }

    private record Seed(Long fileId, String fileMd5, String taskId) {
    }
}
