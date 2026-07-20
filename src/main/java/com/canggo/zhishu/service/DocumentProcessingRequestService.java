package com.canggo.zhishu.service;

import com.canggo.zhishu.exception.CustomException;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentProcessingRequestService {

    private final FileUploadRepository fileUploadRepository;
    private final DocumentProcessingTaskRepository taskRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public DocumentProcessingRequestService(
            FileUploadRepository fileUploadRepository,
            DocumentProcessingTaskRepository taskRepository,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.fileUploadRepository = fileUploadRepository;
        this.taskRepository = taskRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProcessingRequestResult finalizeUpload(
            Long fileUploadId,
            String sourceObjectKey,
            Long estimatedEmbeddingTokens,
            Integer estimatedChunkCount) {
        FileUpload file = fileUploadRepository.findByIdForUpdate(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件记录不存在: " + fileUploadId));

        if (file.getLatestProcessingVersion() > 0) {
            DocumentProcessingTask existing = taskRepository
                    .findByFileUploadIdAndProcessingVersion(
                            fileUploadId,
                            file.getLatestProcessingVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "文件版本已有分配但处理任务缺失: fileUploadId=" + fileUploadId));
            return ProcessingRequestResult.from(existing);
        }

        int processingVersion = Math.max(1, file.getLatestProcessingVersion() + 1);
        if (estimatedEmbeddingTokens != null) {
            file.setEstimatedEmbeddingTokens(estimatedEmbeddingTokens);
        }
        if (estimatedChunkCount != null) {
            file.setEstimatedChunkCount(estimatedChunkCount);
        }
        return createProcessingVersion(file, processingVersion, sourceObjectKey);
    }

    @Transactional
    public ProcessingRequestResult reprocessFailed(String failedTaskId) {
        DocumentProcessingTask failedTask = taskRepository.findByTaskId(failedTaskId)
                .orElseThrow(() -> new CustomException("处理任务不存在", HttpStatus.NOT_FOUND));
        if (failedTask.getStatus() != ProcessingTaskStatus.FAILED) {
            throw new CustomException("只有 FAILED 任务可以重新处理", HttpStatus.CONFLICT);
        }

        FileUpload file = fileUploadRepository.findByIdForUpdate(failedTask.getFileUploadId())
                .orElseThrow(() -> new CustomException("任务关联文件不存在", HttpStatus.NOT_FOUND));
        int failedVersion = failedTask.getProcessingVersion();
        int latestVersion = file.getLatestProcessingVersion();
        if (latestVersion == failedVersion + 1) {
            return taskRepository.findByFileUploadIdAndProcessingVersion(file.getId(), latestVersion)
                    .map(ProcessingRequestResult::from)
                    .orElseThrow(() -> new CustomException("后继版本已分配但任务缺失", HttpStatus.CONFLICT));
        }
        if (latestVersion != failedVersion) {
            throw new CustomException("该失败任务已不是文件最新处理版本", HttpStatus.CONFLICT);
        }

        return createProcessingVersion(
                file,
                failedVersion + 1,
                failedTask.getSourceObjectKey());
    }

    @Transactional
    public ProcessingRequestResult requestReindex(Long fileUploadId) {
        FileUpload file = fileUploadRepository.findByIdForUpdate(fileUploadId)
                .orElseThrow(() -> new CustomException("文档不存在", HttpStatus.NOT_FOUND));
        int latestVersion = file.getLatestProcessingVersion();
        String sourceObjectKey = "merged/" + file.getFileMd5();
        if (latestVersion > 0) {
            DocumentProcessingTask latestTask = taskRepository
                    .findByFileUploadIdAndProcessingVersion(file.getId(), latestVersion)
                    .orElseThrow(() -> new CustomException("文件最新版本任务缺失", HttpStatus.CONFLICT));
            if (latestTask.getStatus() == ProcessingTaskStatus.PENDING
                    || latestTask.getStatus() == ProcessingTaskStatus.PROCESSING
                    || latestTask.getStatus() == ProcessingTaskStatus.RETRY_WAIT) {
                return ProcessingRequestResult.from(latestTask);
            }
            sourceObjectKey = latestTask.getSourceObjectKey();
        }
        return createProcessingVersion(file, latestVersion + 1, sourceObjectKey);
    }

    private ProcessingRequestResult createProcessingVersion(
            FileUpload file,
            int processingVersion,
            String sourceObjectKey) {
        String taskId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(taskId);
        task.setFileUploadId(file.getId());
        task.setProcessingVersion(processingVersion);
        task.setStatus(ProcessingTaskStatus.PENDING);
        task.setCurrentStage(ProcessingStage.DISPATCH);
        task.setRetryCount(0);
        task.setSourceObjectKey(sourceObjectKey);

        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                eventId,
                taskId,
                file.getId(),
                processingVersion,
                sourceObjectKey);

        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(eventId);
        outbox.setTaskId(taskId);
        outbox.setPayload(serialize(requested));
        outbox.setStatus(OutboxStatus.NEW);
        outbox.setRetryCount(0);

        file.setStatus(1);
        file.setLatestProcessingVersion(processingVersion);

        fileUploadRepository.save(file);
        taskRepository.save(task);
        outboxRepository.saveAndFlush(outbox);
        return ProcessingRequestResult.from(task);
    }

    private String serialize(DocumentProcessingRequested requested) {
        try {
            return objectMapper.writeValueAsString(requested);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化文档处理事件", e);
        }
    }

    public record ProcessingRequestResult(
            String taskId,
            Long fileUploadId,
            int processingVersion,
            ProcessingTaskStatus status,
            String sourceObjectKey) {

        private static ProcessingRequestResult from(DocumentProcessingTask task) {
            return new ProcessingRequestResult(
                    task.getTaskId(),
                    task.getFileUploadId(),
                    task.getProcessingVersion(),
                    task.getStatus(),
                    task.getSourceObjectKey());
        }
    }
}
