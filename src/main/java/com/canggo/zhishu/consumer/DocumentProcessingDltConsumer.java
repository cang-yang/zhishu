package com.canggo.zhishu.consumer;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class DocumentProcessingDltConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessingDltConsumer.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentProcessingTaskRepository taskRepository;

    public DocumentProcessingDltConsumer(DocumentProcessingTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic.document-processing-v2-dlt:document-processing-v2-dlt}",
            groupId = "${spring.kafka.consumer.document-processing-v2-dlt-group-id:document-processing-v2-dlt-group}",
            containerFactory = "documentProcessingV2DltKafkaListenerContainerFactory")
    public void processDlt(
            DocumentProcessingRequested requested,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String originalTopic,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false) Integer originalPartition,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) Long originalOffset,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        if (requested == null || requested.taskId() == null || requested.taskId().isBlank()) {
            throw new IllegalArgumentException("DLT 文档处理事件缺少 taskId");
        }

        String error = limit("Kafka DLT exhausted: topic=" + valueOrUnknown(originalTopic)
                + ", partition=" + valueOrUnknown(originalPartition)
                + ", offset=" + valueOrUnknown(originalOffset)
                + ", root=" + valueOrUnknown(exceptionMessage));
        int updated = taskRepository.failPublishedUnownedDelivery(
                requested.taskId(), requested.eventId(), error);
        if (updated == 1) {
            logger.error("文档处理重试耗尽并进入 FAILED: taskId={}, {}", requested.taskId(), error);
        } else {
            logger.info("忽略不再适用的文档处理 DLT: taskId={}, eventId={}",
                    requested.taskId(), requested.eventId());
        }
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private String limit(String value) {
        if (value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
