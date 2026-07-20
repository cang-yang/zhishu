package com.canggo.zhishu.service;

import com.canggo.zhishu.client.EmbeddingClient;
import com.canggo.zhishu.entity.EsDocument;
import com.canggo.zhishu.model.DocumentVector;
import com.canggo.zhishu.repository.DocumentVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(VersionedChunkWriter.class)
class VersionedChunkPersistenceTest {

    @Autowired
    private DocumentVectorRepository repository;
    @Autowired
    private VersionedChunkWriter versionedChunkWriter;

    @Test
    void sameBusinessKeyAndHashIsReusedButDifferentHashIsRejected() throws Exception {
        ParseService parseService = parseService();

        DocumentVector first = parseService.persistVersionedChunk(
                9L, 3, "md5", 1, "stable text", 2, "anchor", "owner", "org", false);
        DocumentVector repeated = parseService.persistVersionedChunk(
                9L, 3, "md5", 1, "stable text", 2, "anchor", "owner", "org", false);

        assertEquals(first.getVectorId(), repeated.getVectorId());
        assertEquals(1, repository.countByFileUploadIdAndProcessingVersion(9L, 3));
        assertEquals(sha256("stable text"), repeated.getContentHash());

        assertThrows(DocumentConsistencyException.class, () -> parseService.persistVersionedChunk(
                9L, 3, "md5", 1, "late different text", 2, "anchor", "owner", "org", false));
        assertEquals("stable text", repository
                .findByFileUploadIdAndProcessingVersionAndChunkId(9L, 3, 1)
                .orElseThrow()
                .getTextContent());
    }

    @Test
    void vectorizationReadsOnlyRequestedVersionAndBuildsDeterministicIds() {
        ParseService parseService = parseService();
        parseService.persistVersionedChunk(20L, 1, "same-md5", 2, "second", null, "a2", "owner", "org", true);
        parseService.persistVersionedChunk(20L, 1, "same-md5", 1, "first", null, "a1", "owner", "org", true);
        parseService.persistVersionedChunk(20L, 2, "same-md5", 1, "new-version", null, "a", "owner", "org", true);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
        VectorizationService vectorizationService = new VectorizationService();
        ReflectionTestUtils.setField(vectorizationService, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(vectorizationService, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(vectorizationService, "documentVectorRepository", repository);
        when(embeddingClient.embedWithUsage(
                eq(List.of("first", "second")), eq("owner"), eq(EmbeddingClient.UsageType.UPLOAD)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        List.of(new float[]{1F}, new float[]{2F}), 8, "model-v2"));

        VectorizationService.PreparedVersion firstRun = vectorizationService.prepareVersion(
                20L, 1, "same-md5", "owner", "org", true, "owner");
        VectorizationService.PreparedVersion repeatedRun = vectorizationService.prepareVersion(
                20L, 1, "same-md5", "owner", "org", true, "owner");

        assertEquals(List.of("20:1:1", "20:1:2"), ids(firstRun.documents()));
        assertEquals(ids(firstRun.documents()), ids(repeatedRun.documents()));
        firstRun.documents().forEach(document -> {
            assertEquals(20L, document.getFileUploadId());
            assertEquals(1, document.getProcessingVersion());
        });

        vectorizationService.indexVersion(firstRun);
        verify(elasticsearchService).bulkIndex(firstRun.documents());
    }

    private ParseService parseService() {
        ParseService service = new ParseService();
        ReflectionTestUtils.setField(service, "documentVectorRepository", repository);
        ReflectionTestUtils.setField(service, "versionedChunkWriter", versionedChunkWriter);
        return service;
    }

    private List<String> ids(List<EsDocument> documents) {
        return documents.stream().map(EsDocument::getId).toList();
    }

    private String sha256(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }
}
