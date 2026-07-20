package com.canggo.zhishu.consumer;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.ProcessingStage;
import com.canggo.zhishu.service.DocumentProcessingTaskService;
import com.canggo.zhishu.service.ParseService;
import com.canggo.zhishu.service.StaleExecutionException;
import com.canggo.zhishu.service.UploadService;
import com.canggo.zhishu.service.VectorizationService;
import io.minio.GetObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DocumentProcessingConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessingConsumer.class);

    private final DocumentProcessingTaskService taskService;
    private final UploadService uploadService;
    private final ParseService parseService;
    private final VectorizationService vectorizationService;

    public DocumentProcessingConsumer(
            DocumentProcessingTaskService taskService,
            UploadService uploadService,
            ParseService parseService,
            VectorizationService vectorizationService) {
        this.taskService = taskService;
        this.uploadService = uploadService;
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
    }

    @KafkaListener(
            topics = "#{kafkaConfig.getDocumentProcessingV2Topic()}",
            groupId = "${spring.kafka.consumer.document-processing-v2-group-id:document-processing-v2-group}",
            containerFactory = "documentProcessingV2KafkaListenerContainerFactory")
    public void processTask(DocumentProcessingRequested requested) {
        Optional<DocumentProcessingTaskService.ProcessingClaim> claimed = taskService.claim(requested);
        if (claimed.isEmpty()) {
            logger.info("Document processing delivery is already owned or terminal, taskId={}", requested.taskId());
            return;
        }

        DocumentProcessingTaskService.ProcessingClaim claim = claimed.orElseThrow();
        ProcessingStage stage = ProcessingStage.DOWNLOAD;
        try {
            taskService.updateStage(claim, stage);
            try (GetObjectResponse fileStream = uploadService
                    .getMergedFileStreamByObjectKey(claim.sourceObjectKey())) {
                stage = ProcessingStage.PARSE;
                taskService.updateStage(claim, stage);
                parseService.parseAndSave(
                        claim.fileUploadId(),
                        claim.processingVersion(),
                        claim.fileMd5(),
                        fileStream,
                        claim.userId(),
                        claim.orgTag(),
                        claim.isPublic());
            }

            stage = ProcessingStage.PERSIST;
            taskService.updateStage(claim, stage);
            stage = ProcessingStage.EMBEDDING;
            taskService.updateStage(claim, stage);
            VectorizationService.PreparedVersion prepared = vectorizationService.prepareVersion(
                    claim.fileUploadId(),
                    claim.processingVersion(),
                    claim.fileMd5(),
                    claim.userId(),
                    claim.orgTag(),
                    claim.isPublic(),
                    claim.userId());

            stage = ProcessingStage.INDEX;
            taskService.updateStage(claim, stage);
            vectorizationService.indexVersion(prepared);

            stage = ProcessingStage.ACTIVATE;
            taskService.updateStage(claim, stage);
            taskService.complete(claim, prepared.usage());
        } catch (Exception failure) {
            try {
                taskService.retryAfterFailure(claim, stage, failure);
            } catch (StaleExecutionException stale) {
                logger.warn("Processing failure arrived after execution ownership was lost, taskId={}",
                        claim.taskId(), stale);
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("Document processing failed", failure);
        }
    }
}
