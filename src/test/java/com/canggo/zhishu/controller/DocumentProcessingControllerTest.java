package com.canggo.zhishu.controller;

import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.model.OutboxStatus;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.model.ProcessingTaskStatus;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import com.canggo.zhishu.service.DocumentProcessingRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@Import({
        DocumentProcessingRequestService.class,
        DocumentProcessingController.class,
        DocumentProcessingControllerTest.Config.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentProcessingControllerTest {

    @Autowired
    private DocumentProcessingController controller;
    @Autowired
    private DocumentProcessingRequestService requestService;
    @Autowired
    private FileUploadRepository fileRepository;
    @Autowired
    private DocumentProcessingTaskRepository taskRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;

    @BeforeEach
    void clearRows() {
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        fileRepository.deleteAll();
    }

    @Test
    void ownerAndAdminCanReadTaskButUnrelatedUserCannot() {
        Seed seed = seed(ProcessingTaskStatus.FAILED, 1, null);

        assertEquals(200, controller.getTask(seed.taskId(), "101", "USER").getStatusCode().value());
        assertEquals(200, controller.getTask(seed.taskId(), "999", "ADMIN").getStatusCode().value());
        ResponseEntity<Map<String, Object>> forbidden = controller.getTask(seed.taskId(), "202", "USER");
        assertEquals(403, forbidden.getStatusCode().value());
        assertEquals(403, forbidden.getBody().get("code"));
    }

    @Test
    void unrelatedUserCannotTriggerEmbeddingSpendForAnotherUsersTask() {
        Seed seed = seed(ProcessingTaskStatus.FAILED, 1, null);

        ResponseEntity<Map<String, Object>> response = controller.reprocess(seed.taskId(), "202", "USER");

        assertEquals(403, response.getStatusCode().value());
        assertEquals(1, taskRepository.count());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void onlyFailedTaskCanBeReprocessed() {
        Seed seed = seed(ProcessingTaskStatus.PROCESSING, 1, null);

        ResponseEntity<Map<String, Object>> response = controller.reprocess(seed.taskId(), "101", "USER");

        assertEquals(409, response.getStatusCode().value());
        assertEquals(1, taskRepository.count());
    }

    @Test
    void reprocessCreatesOneNewVersionAndDoesNotActivateItEarly() {
        Seed seed = seed(ProcessingTaskStatus.FAILED, 1, 0);

        ResponseEntity<Map<String, Object>> first = controller.reprocess(seed.taskId(), "101", "USER");
        ResponseEntity<Map<String, Object>> duplicate = controller.reprocess(seed.taskId(), "101", "USER");

        assertEquals(202, first.getStatusCode().value());
        assertEquals(202, duplicate.getStatusCode().value());
        assertEquals(202, first.getBody().get("code"));
        Map<String, Object> firstData = data(first);
        Map<String, Object> duplicateData = data(duplicate);
        assertEquals(firstData.get("taskId"), duplicateData.get("taskId"));
        assertNotEquals(seed.taskId(), firstData.get("taskId"));
        assertEquals(2, firstData.get("processingVersion"));
        assertEquals("PENDING", firstData.get("status"));
        assertEquals(2, taskRepository.count());
        assertEquals(2, outboxRepository.count());
        FileUpload file = fileRepository.findById(seed.fileId()).orElseThrow();
        assertEquals(2, file.getLatestProcessingVersion());
        assertEquals(0, file.getActiveProcessingVersion());
    }

    @Test
    void concurrentReprocessRequestsReturnTheSameSuccessor() throws Exception {
        Seed seed = seed(ProcessingTaskStatus.FAILED, 1, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<ResponseEntity<Map<String, Object>>> call = () -> {
            ready.countDown();
            start.await();
            return controller.reprocess(seed.taskId(), "101", "USER");
        };

        try {
            Future<ResponseEntity<Map<String, Object>>> first = pool.submit(call);
            Future<ResponseEntity<Map<String, Object>>> second = pool.submit(call);
            ready.await();
            start.countDown();
            ResponseEntity<Map<String, Object>> firstResponse = first.get();
            ResponseEntity<Map<String, Object>> secondResponse = second.get();

            assertEquals(202, firstResponse.getStatusCode().value());
            assertEquals(202, secondResponse.getStatusCode().value());
            assertEquals(data(firstResponse).get("taskId"), data(secondResponse).get("taskId"));
            assertEquals(2, taskRepository.count());
            assertEquals(2, outboxRepository.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legacyReindexEndpointCreatesAsyncVersionAndReturns202() {
        Seed seed = seed(ProcessingTaskStatus.COMPLETED, 1, 1);
        DocumentController legacyController = new DocumentController();
        ReflectionTestUtils.setField(legacyController, "fileUploadRepository", fileRepository);
        ReflectionTestUtils.setField(legacyController, "documentProcessingRequestService", requestService);

        ResponseEntity<?> response = legacyController.reindexDocument(seed.fileMd5(), "101", "USER");

        assertEquals(202, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(202, body.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = (Map<String, Object>) body.get("data");
        assertNotNull(responseData.get("taskId"));
        assertEquals(2, responseData.get("processingVersion"));
        assertEquals("PENDING", responseData.get("status"));
        assertEquals(1, fileRepository.findById(seed.fileId()).orElseThrow().getActiveProcessingVersion());
    }

    @Test
    void legacyReindexReturnsForbiddenWhenMd5ExistsButRequesterIsNotOwner() {
        Seed seed = seed(ProcessingTaskStatus.COMPLETED, 1, 1);
        DocumentController legacyController = new DocumentController();
        ReflectionTestUtils.setField(legacyController, "fileUploadRepository", fileRepository);
        ReflectionTestUtils.setField(legacyController, "documentProcessingRequestService", requestService);

        ResponseEntity<?> response = legacyController.reindexDocument(seed.fileMd5(), "202", "USER");

        assertEquals(403, response.getStatusCode().value());
        assertEquals(1, taskRepository.count());
        assertEquals(1, outboxRepository.count());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private Seed seed(ProcessingTaskStatus status, int version, Integer activeVersion) {
        String fileMd5 = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        FileUpload file = new FileUpload();
        file.setFileMd5(fileMd5);
        file.setFileName("manual.pdf");
        file.setTotalSize(100L);
        file.setStatus(1);
        file.setUserId("101");
        file.setOrgTag("org-a");
        file.setLatestProcessingVersion(version);
        file.setActiveProcessingVersion(activeVersion);
        file = fileRepository.saveAndFlush(file);

        DocumentProcessingTask task = new DocumentProcessingTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setFileUploadId(file.getId());
        task.setProcessingVersion(version);
        task.setStatus(status);
        task.setCurrentStage(status == ProcessingTaskStatus.COMPLETED
                ? ProcessingStage.ACTIVATE
                : ProcessingStage.INDEX);
        task.setRetryCount(status == ProcessingTaskStatus.FAILED ? 5 : 0);
        task.setErrorMessage(status == ProcessingTaskStatus.FAILED ? "injected failure" : null);
        task.setSourceObjectKey("merged/" + fileMd5);
        if (status == ProcessingTaskStatus.PROCESSING) {
            task.setExecutionToken("live-token");
        }
        task = taskRepository.saveAndFlush(task);

        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setTaskId(task.getTaskId());
        outbox.setPayload("{}");
        outbox.setStatus(OutboxStatus.PUBLISHED);
        outbox.setRetryCount(0);
        outboxRepository.saveAndFlush(outbox);
        return new Seed(file.getId(), fileMd5, task.getTaskId());
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private record Seed(Long fileId, String fileMd5, String taskId) {
    }
}
