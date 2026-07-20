package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.DocumentVector;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.model.OutboxStatus;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.model.ProcessingTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
class DocumentProcessingSchemaTest {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentProcessingTaskRepository taskRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Test
    void rejectsDuplicateTaskId() {
        FileUpload file = saveFile("11111111111111111111111111111111", "owner-1");
        taskRepository.saveAndFlush(newTask(file.getId(), 1, "task-duplicate", ProcessingTaskStatus.PENDING));

        assertThrows(DataIntegrityViolationException.class, () ->
                taskRepository.saveAndFlush(newTask(file.getId(), 2, "task-duplicate", ProcessingTaskStatus.PENDING)));
    }

    @Test
    void rejectsDuplicateFileAndProcessingVersion() {
        FileUpload file = saveFile("22222222222222222222222222222222", "owner-2");
        taskRepository.saveAndFlush(newTask(file.getId(), 1, "task-version-a", ProcessingTaskStatus.PENDING));

        assertThrows(DataIntegrityViolationException.class, () ->
                taskRepository.saveAndFlush(newTask(file.getId(), 1, "task-version-b", ProcessingTaskStatus.PENDING)));
    }

    @Test
    void rejectsSecondOutboxEventForSameTask() {
        FileUpload file = saveFile("33333333333333333333333333333333", "owner-3");
        DocumentProcessingTask task = taskRepository.saveAndFlush(
                newTask(file.getId(), 1, "task-outbox", ProcessingTaskStatus.PENDING));
        outboxRepository.saveAndFlush(newOutbox("event-a", task.getTaskId()));

        assertThrows(DataIntegrityViolationException.class, () ->
                outboxRepository.saveAndFlush(newOutbox("event-b", task.getTaskId())));
    }

    @Test
    void versionedChunkBusinessKeyIsUniqueButLegacyNullRowsRemainCompatible() {
        DocumentVector legacyA = newVector(null, null, 7, "legacy-a", null);
        DocumentVector legacyB = newVector(null, null, 7, "legacy-b", null);
        documentVectorRepository.saveAndFlush(legacyA);
        documentVectorRepository.saveAndFlush(legacyB);

        DocumentVector versioned = newVector(42L, 1, 7, "versioned", "a".repeat(64));
        documentVectorRepository.saveAndFlush(versioned);

        assertThrows(DataIntegrityViolationException.class, () ->
                documentVectorRepository.saveAndFlush(
                        newVector(42L, 1, 7, "duplicate", "b".repeat(64))));
    }

    @Test
    void claimIsAtomicAndOnlyPendingOrRetryWaitCanBeClaimed() {
        FileUpload file = saveFile("44444444444444444444444444444444", "owner-4");
        DocumentProcessingTask pending = taskRepository.saveAndFlush(
                newTask(file.getId(), 1, "task-pending", ProcessingTaskStatus.PENDING));
        DocumentProcessingTask completed = taskRepository.saveAndFlush(
                newTask(file.getId(), 2, "task-completed", ProcessingTaskStatus.COMPLETED));

        assertEquals(1, taskRepository.claim(pending.getTaskId(), "token-a", 60));
        assertEquals(0, taskRepository.claim(pending.getTaskId(), "token-b", 60));
        assertEquals(0, taskRepository.claim(completed.getTaskId(), "token-c", 60));

        taskRepository.flush();
        DocumentProcessingTask claimed = taskRepository.findByTaskId(pending.getTaskId()).orElseThrow();
        assertEquals(ProcessingTaskStatus.PROCESSING, claimed.getStatus());
        assertEquals("token-a", claimed.getExecutionToken());
    }

    @Test
    void retryWaitCanBeClaimedButWrongTokenCannotMutateProcessingTask() {
        FileUpload file = saveFile("55555555555555555555555555555555", "owner-5");
        DocumentProcessingTask retryWait = taskRepository.saveAndFlush(
                newTask(file.getId(), 1, "task-retry", ProcessingTaskStatus.RETRY_WAIT));

        assertEquals(1, taskRepository.claim(retryWait.getTaskId(), "right-token", 60));
        assertEquals(0, taskRepository.updateStage(
                retryWait.getTaskId(), "wrong-token", ProcessingStage.PARSE.name(), 60));
        assertEquals(0, taskRepository.complete(retryWait.getTaskId(), "wrong-token"));
        assertEquals(0, taskRepository.retryAfterFailure(
                retryWait.getTaskId(), "wrong-token", ProcessingStage.DOWNLOAD.name(), "boom"));

        taskRepository.flush();
        DocumentProcessingTask unchanged = taskRepository.findByTaskId(retryWait.getTaskId()).orElseThrow();
        assertEquals(ProcessingTaskStatus.PROCESSING, unchanged.getStatus());
        assertEquals(ProcessingStage.DISPATCH, unchanged.getCurrentStage());
        assertEquals("right-token", unchanged.getExecutionToken());
    }

    private FileUpload saveFile(String md5, String owner) {
        FileUpload file = new FileUpload();
        file.setFileMd5(md5);
        file.setFileName(md5 + ".pdf");
        file.setTotalSize(128L);
        file.setStatus(1);
        file.setUserId(owner);
        return fileUploadRepository.saveAndFlush(file);
    }

    private DocumentProcessingTask newTask(
            Long fileUploadId,
            int version,
            String taskId,
            ProcessingTaskStatus status) {
        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(taskId);
        task.setFileUploadId(fileUploadId);
        task.setProcessingVersion(version);
        task.setStatus(status);
        task.setCurrentStage(ProcessingStage.DISPATCH);
        task.setRetryCount(0);
        task.setSourceObjectKey("merged/" + fileUploadId);
        return task;
    }

    private OutboxEvent newOutbox(String eventId, String taskId) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setTaskId(taskId);
        event.setPayload("{\"taskId\":\"" + taskId + "\"}");
        event.setStatus(OutboxStatus.NEW);
        event.setRetryCount(0);
        return event;
    }

    private DocumentVector newVector(
            Long fileUploadId,
            Integer version,
            int chunkId,
            String text,
            String contentHash) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(UUID.randomUUID().toString().replace("-", ""));
        vector.setChunkId(chunkId);
        vector.setTextContent(text);
        vector.setUserId("owner");
        vector.setFileUploadId(fileUploadId);
        vector.setProcessingVersion(version);
        vector.setContentHash(contentHash);
        return vector;
    }
}
