package com.canggo.zhishu.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsIndexInitializerMappingTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indices;

    private EsIndexInitializer initializer;

    @BeforeEach
    void setUp() throws Exception {
        initializer = new EsIndexInitializer();
        ReflectionTestUtils.setField(initializer, "esClient", client);
        ReflectionTestUtils.setField(initializer, "host", "localhost");
        ReflectionTestUtils.setField(initializer, "port", 9200);
        ReflectionTestUtils.setField(initializer, "scheme", "http");
        ReflectionTestUtils.setField(initializer, "username", "elastic");
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
    }

    @Test
    void existingIndexReceivesAdditiveMappingAndTypeValidation() throws Exception {
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(mapping("long", "integer"));

        initializer.run();

        verify(indices).putMapping(any(PutMappingRequest.class));
        verify(indices).getMapping(any(GetMappingRequest.class));
    }

    @Test
    void incompatibleExistingFieldTypeFailsClosed() throws Exception {
        when(indices.getMapping(any(GetMappingRequest.class))).thenReturn(mapping("keyword", "integer"));

        assertThrows(RuntimeException.class, () -> initializer.run());
    }

    private GetMappingResponse mapping(String fileUploadIdType, String processingVersionType) {
        return GetMappingResponse.of(response -> response.result("knowledge_base", record -> record.mappings(mapping -> {
            if ("long".equals(fileUploadIdType)) {
                mapping.properties("fileUploadId", property -> property.long_(type -> type));
            } else {
                mapping.properties("fileUploadId", property -> property.keyword(type -> type));
            }
            if ("integer".equals(processingVersionType)) {
                mapping.properties("processingVersion", property -> property.integer(type -> type));
            } else {
                mapping.properties("processingVersion", property -> property.keyword(type -> type));
            }
            return mapping;
        })));
    }
}
