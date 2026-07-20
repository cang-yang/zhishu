package com.canggo.zhishu.service;

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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@Import({DocumentProcessingRequestService.class, DocumentProcessingRequestServiceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentProcessingRequestServiceTest {

    @Autowired
    private DocumentProcessingRequestService requestService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentProcessingTaskRepository taskRepository;

    @SpyBean
    private OutboxEventRepository outboxRepository;

    @Test
    void finalizeCommitsFileTaskAndOutboxTogether() {
        FileUpload file = saveUploadingFile("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "owner-a");

        DocumentProcessingRequestService.ProcessingRequestResult result = requestService.finalizeUpload(
                file.getId(), "merged/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 123L, 4);

        FileUpload committedFile = fileUploadRepository.findById(file.getId()).orElseThrow();
        DocumentProcessingTask task = taskRepository.findByTaskId(result.taskId()).orElseThrow();
        OutboxEvent outbox = outboxRepository.findByTaskId(result.taskId()).orElseThrow();

        assertEquals(1, committedFile.getStatus());
        assertEquals(1, committedFile.getLatestProcessingVersion());
        assertNull(committedFile.getActiveProcessingVersion());
        assertNotNull(committedFile.getMergedAt());
        assertEquals(123L, committedFile.getEstimatedEmbeddingTokens());
        assertEquals(4, committedFile.getEstimatedChunkCount());
        assertEquals(ProcessingTaskStatus.PENDING, task.getStatus());
        assertEquals(ProcessingStage.DISPATCH, task.getCurrentStage());
        assertEquals(OutboxStatus.NEW, outbox.getStatus());
        assertTrue(outbox.getPayload().contains(result.taskId()));
        assertTrue(outbox.getPayload().contains("\"processingVersion\":1"));
    }

    @Test
    void outboxFailureRollsBackFileAndTask() {
        FileUpload file = saveUploadingFile("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "owner-b");
        doThrow(new DataIntegrityViolationException("injected outbox failure"))
                .when(outboxRepository).saveAndFlush(any(OutboxEvent.class));

        assertThrows(DataIntegrityViolationException.class, () -> requestService.finalizeUpload(
                file.getId(), "merged/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", null, null));

        FileUpload rolledBackFile = fileUploadRepository.findById(file.getId()).orElseThrow();
        assertEquals(0, rolledBackFile.getStatus());
        assertEquals(0, rolledBackFile.getLatestProcessingVersion());
        assertNull(rolledBackFile.getActiveProcessingVersion());
        assertEquals(0, taskRepository.count());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void duplicateFinalizeReturnsExistingTaskWithoutIncrementingVersion() {
        FileUpload file = saveUploadingFile("cccccccccccccccccccccccccccccccc", "owner-c");

        DocumentProcessingRequestService.ProcessingRequestResult first = requestService.finalizeUpload(
                file.getId(), "merged/cccccccccccccccccccccccccccccccc", 10L, 2);
        DocumentProcessingRequestService.ProcessingRequestResult second = requestService.finalizeUpload(
                file.getId(), "merged/cccccccccccccccccccccccccccccccc", 99L, 99);

        assertEquals(first.taskId(), second.taskId());
        assertEquals(1, second.processingVersion());
        assertEquals(1, taskRepository.count());
        assertEquals(1, outboxRepository.count());
        FileUpload unchanged = fileUploadRepository.findById(file.getId()).orElseThrow();
        assertEquals(1, unchanged.getLatestProcessingVersion());
        assertEquals(10L, unchanged.getEstimatedEmbeddingTokens());
        assertEquals(2, unchanged.getEstimatedChunkCount());
    }

    private FileUpload saveUploadingFile(String md5, String owner) {
        FileUpload file = new FileUpload();
        file.setFileMd5(md5);
        file.setFileName(md5 + ".pdf");
        file.setTotalSize(256L);
        file.setStatus(0);
        file.setUserId(owner);
        file.setLatestProcessingVersion(0);
        file.setActiveProcessingVersion(null);
        return fileUploadRepository.saveAndFlush(file);
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
