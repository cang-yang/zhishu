package com.canggo.zhishu.config;

import com.canggo.zhishu.model.DocumentProcessingRequested;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = OutboxKafkaIntegrationTest.TestApplication.class, properties = {
        "spring.kafka.topic.file-processing=legacy-processing",
        "spring.kafka.topic.dlt=legacy-processing-dlt",
        "spring.kafka.topic.document-processing-v2=document-processing-v2",
        "spring.kafka.topic.document-processing-v2-dlt=document-processing-v2-dlt",
        "spring.kafka.consumer.group-id=legacy-group",
        "spring.kafka.consumer.document-processing-v2-group-id=document-processing-v2-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=*",
        "spring.kafka.producer.transactional-id-prefix=zh-f03-it-",
        "spring.kafka.topic.partitions=1",
        "spring.kafka.topic.replication-factor=1"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "legacy-processing",
                "legacy-processing-dlt",
                "document-processing-v2",
                "document-processing-v2-dlt",
                "document-processing-v2-abort",
                "document-processing-v2-duplicate"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class OutboxKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private ProducerFactory<String, Object> producerFactory;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    @Qualifier("documentProcessingV2ConsumerFactory")
    private ConsumerFactory<String, Object> v2ConsumerFactory;
    @Autowired
    private FailingV2Listener failingV2Listener;

    @Test
    void transactionalTemplatePublishesAndV2ConsumerIsReadCommitted() {
        assertEquals("zh-f03-it-",
                ((DefaultKafkaProducerFactory<String, Object>) producerFactory)
                        .getTransactionIdPrefix());
        assertEquals("read_committed",
                v2ConsumerFactory.getConfigurationProperties().get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
        assertEquals(20_000,
                producerFactory.getConfigurationProperties().get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                "event-1", "task-1", 1L, 1, "merged/md5");

        kafkaTemplate.executeInTransaction(operations -> {
            operations.send("document-processing-v2", "1", requested).join();
            return null;
        });

        try (Consumer<String, Object> consumer = v2ConsumerFactory.createConsumer("it-read-", "tx")) {
            broker.consumeFromAnEmbeddedTopic(consumer, "document-processing-v2");
            ConsumerRecord<String, Object> record = KafkaTestUtils.getSingleRecord(
                    consumer, "document-processing-v2", Duration.ofSeconds(10));
            assertNotNull(record.value());
        }
    }

    @Test
    void abortedKafkaTransactionIsInvisibleToReadCommittedConsumer() {
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                "event-abort", "task-abort", 2L, 1, "merged/abort");

        assertThrows(RuntimeException.class, () -> kafkaTemplate.executeInTransaction(operations -> {
            operations.send("document-processing-v2-abort", "abort", requested).join();
            throw new RuntimeException("injected transaction abort");
        }));

        try (Consumer<String, Object> consumer = v2ConsumerFactory.createConsumer("it-abort-", "tx")) {
            broker.consumeFromAnEmbeddedTopic(consumer, "document-processing-v2-abort");
            assertEquals(0, KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2)).count());
        }
    }

    @Test
    void acknowledgedMessageCanBeRepeatedWithTheSameStablePayload() {
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                "event-repeat", "task-repeat", 3L, 4, "merged/repeat");

        kafkaTemplate.executeInTransaction(operations -> {
            operations.send("document-processing-v2-duplicate", "3", requested).join();
            return null;
        });
        kafkaTemplate.executeInTransaction(operations -> {
            operations.send("document-processing-v2-duplicate", "3", requested).join();
            return null;
        });

        try (Consumer<String, Object> consumer = v2ConsumerFactory.createConsumer("it-repeat-", "tx")) {
            broker.consumeFromAnEmbeddedTopic(consumer, "document-processing-v2-duplicate");
            List<DocumentProcessingRequested> values = new ArrayList<>();
            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10)).forEach(record ->
                    values.add((DocumentProcessingRequested) record.value()));
            assertEquals(List.of(requested, requested), values);
        }
    }

    @Test
    void v2DefaultErrorHandlerRetriesSameRecordThenPublishesToDedicatedDlt() {
        DocumentProcessingRequested requested = new DocumentProcessingRequested(
                "event-handler-dlt", "task-handler-dlt", 4L, 1, "merged/dlt");

        try (Consumer<String, Object> consumer = v2ConsumerFactory.createConsumer("it-dlt-", "recoverer")) {
            broker.consumeFromAnEmbeddedTopic(consumer, "document-processing-v2-dlt");
            kafkaTemplate.executeInTransaction(operations -> {
                operations.send("document-processing-v2", "4", requested).join();
                return null;
            });
            ConsumerRecord<String, Object> record = KafkaTestUtils.getSingleRecord(
                    consumer, "document-processing-v2-dlt", Duration.ofSeconds(30));
            assertEquals("4", record.key());
            assertEquals(requested, record.value());
            assertEquals(5, failingV2Listener.attempts.get());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableKafka
    @Import(KafkaConfig.class)
    static class TestApplication {
        @Bean
        FailingV2Listener failingV2Listener() {
            return new FailingV2Listener();
        }
    }

    static class FailingV2Listener {
        private final AtomicInteger attempts = new AtomicInteger();

        @KafkaListener(
                topics = "${spring.kafka.topic.document-processing-v2}",
                groupId = "outbox-kafka-integration-failing-listener",
                containerFactory = "documentProcessingV2KafkaListenerContainerFactory")
        void receive(DocumentProcessingRequested requested) {
            if ("task-handler-dlt".equals(requested.taskId())) {
                attempts.incrementAndGet();
                throw new RuntimeException("injected listener failure");
            }
        }
    }
}
