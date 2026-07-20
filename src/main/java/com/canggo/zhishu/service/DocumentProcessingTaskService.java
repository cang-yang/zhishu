package com.canggo.zhishu.service;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentProcessingTaskService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessingTaskService.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentProcessingTaskRepository taskRepository;
    private final FileUploadRepository fileUploadRepository;
    private final OutboxEventRepository outboxRepository;
    private final int leaseSeconds;
    private final int maxRecoveries;
    private final int recoveryBatchSize;

    public DocumentProcessingTaskService(
            DocumentProcessingTaskRepository taskRepository,
            FileUploadRepository fileUploadRepository,
            OutboxEventRepository outboxRepository,
            @Value("${document-processing.lease-seconds:1800}") int leaseSeconds,
            @Value("${document-processing.max-recoveries:3}") int maxRecoveries,
            @Value("${document-processing.reaper.batch-size:50}") int recoveryBatchSize) {
        this.taskRepository = taskRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.outboxRepository = outboxRepository;
        this.leaseSeconds = leaseSeconds;
        this.maxRecoveries = maxRecoveries;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    @Transactional
    public Optional<ProcessingClaim> claim(DocumentProcessingRequested requested) {
        String executionToken = UUID.randomUUID().toString();
        int claimed = taskRepository.claimRequested(
                requested.taskId(),
                requested.fileUploadId(),
                requested.processingVersion(),
                requested.sourceObjectKey(),
                executionToken,
                leaseSeconds);
        if (claimed != 1) {
            return Optional.empty();
        }

        DocumentProcessingTask task = taskRepository.findByTaskId(requested.taskId())
                .orElseThrow(() -> new IllegalStateException("Claimed processing task is missing"));
        FileUpload file = fileUploadRepository.findById(task.getFileUploadId())
                .orElseThrow(() -> new IllegalStateException("FileUpload for claimed task is missing"));
        return Optional.of(new ProcessingClaim(
                task.getTaskId(),
                executionToken,
                task.getFileUploadId(),
                task.getProcessingVersion(),
                task.getSourceObjectKey(),
                file.getFileMd5(),
                file.getUserId(),
                file.getOrgTag(),
                file.isPublic()));
    }

    public void updateStage(ProcessingClaim claim, ProcessingStage stage) {
        int updated = taskRepository.updateStage(
                claim.taskId(), claim.executionToken(), stage.name(), leaseSeconds);
        requireExecution(updated, claim, "renew stage " + stage);
    }

    public void retryAfterFailure(ProcessingClaim claim, ProcessingStage stage, Throwable failure) {
        int updated = taskRepository.retryAfterFailure(
                claim.taskId(),
                claim.executionToken(),
                stage.name(),
                abbreviate(rootMessage(failure)));
        requireExecution(updated, claim, "record retry failure");
    }

    @Transactional
    public void complete(
            ProcessingClaim claim,
            VectorizationService.VectorizationUsageResult usage) {
        int activated = fileUploadRepository.activateVersionIfLatest(
                claim.fileUploadId(),
                claim.processingVersion(),
                usage.actualEmbeddingTokens(),
                usage.actualChunkCount());
        if (activated != 1) {
            throw new StaleExecutionException(
                    "Processing version is no longer latest for file " + claim.fileUploadId());
        }
        int completed = taskRepository.complete(claim.taskId(), claim.executionToken());
        requireExecution(completed, claim, "complete and activate");
    }

    @Transactional
    public int recoverExpiredTasks() {
        List<String> expiredTaskIds = taskRepository.findExpiredTaskIds(recoveryBatchSize);
        int recovered = 0;
        for (String taskId : expiredTaskIds) {
            String reason = "Processing lease expired";
            if (taskRepository.recoverExpiredToRetry(taskId, reason, maxRecoveries) == 1) {
                if (outboxRepository.resetForTask(taskId, reason) != 1) {
                    String invariantFailure = "Processing lease expired but unique outbox event is missing";
                    if (taskRepository.failReleasedRetryWait(taskId, invariantFailure) != 1) {
                        throw new IllegalStateException(
                                "Cannot fail task whose unique outbox event is missing: " + taskId);
                    }
                    logger.error("Lease recovery stopped for corrupt task with missing outbox, taskId={}", taskId);
                }
                recovered++;
                continue;
            }
            if (taskRepository.failExpired(taskId, reason, maxRecoveries) == 1) {
                recovered++;
            }
        }
        return recovered;
    }

    private void requireExecution(int updated, ProcessingClaim claim, String operation) {
        if (updated != 1) {
            throw new StaleExecutionException(
                    "Lost execution ownership while attempting to " + operation + " for task " + claim.taskId());
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    private String abbreviate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    public record ProcessingClaim(
            String taskId,
            String executionToken,
            Long fileUploadId,
            int processingVersion,
            String sourceObjectKey,
            String fileMd5,
            String userId,
            String orgTag,
            boolean isPublic) {
    }
}
