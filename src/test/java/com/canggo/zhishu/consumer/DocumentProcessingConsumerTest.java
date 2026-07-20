package com.canggo.zhishu.consumer;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.service.DocumentProcessingTaskService;
import com.canggo.zhishu.service.ParseService;
import com.canggo.zhishu.service.UploadService;
import com.canggo.zhishu.service.VectorizationService;
import io.minio.GetObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingConsumerTest {

    @Mock
    private DocumentProcessingTaskService taskService;
    @Mock
    private UploadService uploadService;
    @Mock
    private ParseService parseService;
    @Mock
    private VectorizationService vectorizationService;

    private DocumentProcessingConsumer consumer;
    private DocumentProcessingRequested requested;
    private DocumentProcessingTaskService.ProcessingClaim claim;

    @BeforeEach
    void setUp() {
        consumer = new DocumentProcessingConsumer(taskService, uploadService, parseService, vectorizationService);
        requested = new DocumentProcessingRequested("event-1", "task-1", 10L, 2, "merged/md5");
        claim = new DocumentProcessingTaskService.ProcessingClaim(
                "task-1", "token-1", 10L, 2, "merged/md5", "md5", "owner", "org-a", true);
    }

    @Test
    void duplicateDeliveryWithoutClaimIsAcknowledgedAsNoOp() throws Exception {
        when(taskService.claim(requested)).thenReturn(Optional.empty());

        consumer.processTask(requested);

        verify(uploadService, never()).getMergedFileStreamByObjectKey("merged/md5");
        verify(parseService, never()).parseAndSave(
                10L, 2, "md5", null, "owner", "org-a", true);
        verify(vectorizationService, never()).prepareVersion(
                10L, 2, "md5", "owner", "org-a", true, "owner");
    }

    @Test
    void duplicateMessageIsProcessedOnlyByTheSingleSuccessfulClaim() throws Exception {
        GetObjectResponse stream = mock(GetObjectResponse.class);
        VectorizationService.PreparedVersion prepared = prepared();
        when(taskService.claim(requested)).thenReturn(Optional.of(claim), Optional.empty());
        when(uploadService.getMergedFileStreamByObjectKey("merged/md5")).thenReturn(stream);
        when(vectorizationService.prepareVersion(
                10L, 2, "md5", "owner", "org-a", true, "owner")).thenReturn(prepared);

        consumer.processTask(requested);
        consumer.processTask(requested);

        verify(parseService, times(1)).parseAndSave(
                10L, 2, "md5", stream, "owner", "org-a", true);
        verify(vectorizationService, times(1)).indexVersion(prepared);
        verify(taskService, times(1)).complete(claim, prepared.usage());
    }

    @Test
    void successfulExecutionRenewsStageBoundariesBeforeActivation() throws Exception {
        GetObjectResponse stream = mock(GetObjectResponse.class);
        VectorizationService.PreparedVersion prepared = prepared();
        when(taskService.claim(requested)).thenReturn(Optional.of(claim));
        when(uploadService.getMergedFileStreamByObjectKey("merged/md5")).thenReturn(stream);
        when(vectorizationService.prepareVersion(
                10L, 2, "md5", "owner", "org-a", true, "owner")).thenReturn(prepared);

        consumer.processTask(requested);

        InOrder order = inOrder(taskService, uploadService, parseService, vectorizationService);
        order.verify(taskService).updateStage(claim, ProcessingStage.DOWNLOAD);
        order.verify(uploadService).getMergedFileStreamByObjectKey("merged/md5");
        order.verify(taskService).updateStage(claim, ProcessingStage.PARSE);
        order.verify(parseService).parseAndSave(10L, 2, "md5", stream, "owner", "org-a", true);
        order.verify(taskService).updateStage(claim, ProcessingStage.PERSIST);
        order.verify(taskService).updateStage(claim, ProcessingStage.EMBEDDING);
        order.verify(vectorizationService).prepareVersion(10L, 2, "md5", "owner", "org-a", true, "owner");
        order.verify(taskService).updateStage(claim, ProcessingStage.INDEX);
        order.verify(vectorizationService).indexVersion(prepared);
        order.verify(taskService).updateStage(claim, ProcessingStage.ACTIVATE);
        order.verify(taskService).complete(claim, prepared.usage());
    }

    @Test
    void bulkFailureNeverCompletesOrActivatesAndIsRethrownForKafkaRetry() throws Exception {
        GetObjectResponse stream = mock(GetObjectResponse.class);
        VectorizationService.PreparedVersion prepared = prepared();
        RuntimeException bulkFailure = new RuntimeException("partial bulk failure");
        when(taskService.claim(requested)).thenReturn(Optional.of(claim));
        when(uploadService.getMergedFileStreamByObjectKey("merged/md5")).thenReturn(stream);
        when(vectorizationService.prepareVersion(
                10L, 2, "md5", "owner", "org-a", true, "owner")).thenReturn(prepared);
        org.mockito.Mockito.doThrow(bulkFailure).when(vectorizationService).indexVersion(prepared);

        assertThrows(RuntimeException.class, () -> consumer.processTask(requested));

        verify(taskService).retryAfterFailure(claim, ProcessingStage.INDEX, bulkFailure);
        verify(taskService, never()).complete(claim, prepared.usage());
        verify(taskService, never()).updateStage(claim, ProcessingStage.ACTIVATE);
    }

    private VectorizationService.PreparedVersion prepared() {
        return new VectorizationService.PreparedVersion(
                List.of(),
                new VectorizationService.VectorizationUsageResult(11, 2, "model-v1"));
    }
}
