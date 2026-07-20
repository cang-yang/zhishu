package com.canggo.zhishu.service;

import com.canggo.zhishu.config.MinioConfig;
import com.canggo.zhishu.model.ChunkInfo;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.repository.ChunkInfoRepository;
import com.canggo.zhishu.repository.DocumentVectorRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceComposeTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private DocumentVectorRepository documentVectorRepository;
    @Mock
    private ElasticsearchService elasticsearchService;
    @Mock
    private MinioClient minioClient;
    @Mock
    private FileUploadRepository fileUploadRepository;
    @Mock
    private ChunkInfoRepository chunkInfoRepository;
    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(uploadService, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
        ReflectionTestUtils.setField(uploadService, "bucketName", "uploads");
    }

    @Test
    void existingMergedObjectIsReusedWithoutReadingOrComposingChunks() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));

        String objectKey = uploadService.composeOrReuseMergedObject("md5", "test.pdf", "owner");

        assertEquals("merged/md5", objectKey);
        verifyNoInteractions(chunkInfoRepository);
        verify(minioClient, never()).composeObject(any());
    }

    @Test
    void failedChunkRemovalKeepsRedisAndMysqlMetadataForRetry() throws Exception {
        ChunkInfo chunk = new ChunkInfo();
        chunk.setFileMd5("md5");
        chunk.setChunkIndex(0);
        chunk.setStoragePath("chunks/md5/0");
        when(chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc("md5"))
                .thenReturn(List.of(chunk));
        doThrow(new RuntimeException("minio unavailable"))
                .when(minioClient).removeObject(any());

        assertThrows(RuntimeException.class, () -> uploadService.cleanupUploadedChunks("md5", "owner"));

        verify(redisTemplate, never()).delete(any(String.class));
        verify(chunkInfoRepository, never()).deleteByFileMd5("md5");
    }

    @Test
    void rapidSecondStageOnlyReusesObjectAndDoesNotCopyMysqlOrEsResults() throws Exception {
        FileUpload file = new FileUpload();
        file.setFileMd5("md5");
        file.setUserId("owner");
        file.setIsRapidUpload(true);
        file.setEstimatedEmbeddingTokens(12L);
        file.setEstimatedChunkCount(2);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "owner"))
                .thenReturn(Optional.of(file));
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));
        when(minioClient.getPresignedObjectUrl(any())).thenReturn("https://minio/merged/md5");

        UploadService.InstantTransmissionDeterminationAttributes result =
                uploadService.secondStageInstantUploadDetermination("md5", "test.pdf", "owner");

        assertTrue(result.instantUpload());
        assertEquals(12L, result.estimatedTokens());
        assertEquals(2, result.estimatedChunkCount());
        verifyNoInteractions(documentVectorRepository, elasticsearchService);
    }
}
