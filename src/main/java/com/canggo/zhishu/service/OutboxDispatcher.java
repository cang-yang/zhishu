package com.canggo.zhishu.service;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import com.canggo.zhishu.model.OutboxEvent;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxEventRepository outboxRepository;
    private final DocumentProcessingTaskRepository taskRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;
    private final int staleSeconds;
    private final int sendTimeoutSeconds;
    private final int retryDelaySeconds;
    private final int maxAttempts;

    public OutboxDispatcher(
            OutboxEventRepository outboxRepository,
            DocumentProcessingTaskRepository taskRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${spring.kafka.topic.document-processing-v2:document-processing-v2}") String topic,
            @Value("${document-processing.outbox.batch-size:20}") int batchSize,
            @Value("${document-processing.outbox.stale-seconds:60}") int staleSeconds,
            @Value("${document-processing.outbox.send-timeout-seconds:20}") int sendTimeoutSeconds,
            @Value("${document-processing.outbox.retry-delay-seconds:3}") int retryDelaySeconds,
            @Value("${document-processing.outbox.max-attempts:5}") int maxAttempts) {
        if (staleSeconds < sendTimeoutSeconds) {
            throw new IllegalArgumentException("Outbox staleSeconds 必须不小于 Kafka send timeout");
        }
        this.outboxRepository = outboxRepository;
        this.taskRepository = taskRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = batchSize;
        this.staleSeconds = staleSeconds;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(
            initialDelayString = "${document-processing.outbox.initial-delay-ms:1000}",
            fixedDelayString = "${document-processing.outbox.scan-delay-ms:1000}")
    public void dispatchBatch() {
        List<String> candidates = outboxRepository.findDispatchCandidates(batchSize, staleSeconds);
        for (String eventId : candidates) {
            if (outboxRepository.claim(eventId, staleSeconds) != 1) {
                continue;
            }
            if (!dispatchClaimed(eventId)) {
                break;
            }
        }
    }

    private boolean dispatchClaimed(String eventId) {
        OutboxEvent event = outboxRepository.findByEventId(eventId).orElse(null);
        if (event == null) {
            return true;
        }
        try {
            DocumentProcessingRequested requested = objectMapper.readValue(
                    event.getPayload(), DocumentProcessingRequested.class);
            kafkaTemplate.executeInTransaction(operations -> {
                try {
                    operations.send(topic, String.valueOf(requested.fileUploadId()), requested)
                            .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                    return null;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new KafkaDispatchException("Kafka 发送被中断", interrupted);
                } catch (Exception sendFailure) {
                    throw new KafkaDispatchException("Kafka 发送或确认失败", sendFailure);
                }
            });
            outboxRepository.markPublished(eventId);
            return true;
        } catch (Exception failure) {
            String errorMessage = abbreviate(rootMessage(failure));
            int dead = outboxRepository.markDeadIfExhausted(eventId, errorMessage, maxAttempts);
            if (dead == 1) {
                taskRepository.failPendingDispatch(event.getTaskId(), errorMessage);
            } else {
                outboxRepository.rescheduleIfRetryable(
                        eventId, errorMessage, retryDelaySeconds, maxAttempts);
            }
            logger.warn("Outbox 投递失败: eventId={}, taskId={}, dead={}",
                    eventId, event.getTaskId(), dead == 1, failure);
            return !Thread.currentThread().isInterrupted();
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

    private static final class KafkaDispatchException extends RuntimeException {
        private KafkaDispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
