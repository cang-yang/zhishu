package com.canggo.zhishu.service;

import com.canggo.zhishu.client.EmbeddingClient;
import com.canggo.zhishu.model.DocumentVector;
import com.canggo.zhishu.entity.EsDocument;
import com.canggo.zhishu.entity.TextChunk;
import com.canggo.zhishu.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            chunks.get(i).getPageNumber(),
                            chunks.get(i).getAnchorText(),
                            vectors.get(i),
                            embeddingResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        }
    }

    public PreparedVersion prepareVersion(
            Long fileUploadId,
            int processingVersion,
            String fileMd5,
            String userId,
            String orgTag,
            boolean isPublic,
            String requesterId) {
        try {
            List<DocumentVector> chunks = documentVectorRepository
                    .findByFileUploadIdAndProcessingVersionOrderByChunkIdAsc(
                            fileUploadId, processingVersion);
            if (chunks.isEmpty()) {
                return new PreparedVersion(
                        List.of(),
                        new VectorizationUsageResult(
                                0, 0, embeddingClient.currentModelVersion()));
            }

            List<String> texts = chunks.stream()
                    .map(DocumentVector::getTextContent)
                    .toList();
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD);
            if (embeddingResult.vectors().size() != chunks.size()) {
                throw new DocumentConsistencyException(
                        "Embedding vector count does not match chunk count for fileUploadId="
                                + fileUploadId + ", version=" + processingVersion);
            }

            List<EsDocument> documents = IntStream.range(0, chunks.size())
                    .mapToObj(index -> toVersionedEsDocument(
                            fileUploadId,
                            processingVersion,
                            fileMd5,
                            userId,
                            orgTag,
                            isPublic,
                            chunks.get(index),
                            embeddingResult.vectors().get(index),
                            embeddingResult.modelVersion()))
                    .toList();
            return new PreparedVersion(
                    documents,
                    new VectorizationUsageResult(
                            embeddingResult.totalTokens(),
                            chunks.size(),
                            embeddingResult.modelVersion()));
        } catch (Exception failure) {
            logger.error(
                    "Versioned vectorization preparation failed, fileUploadId={}, version={}",
                    fileUploadId,
                    processingVersion,
                    failure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException("Versioned vectorization preparation failed", failure);
        }
    }

    public void indexVersion(PreparedVersion preparedVersion) {
        if (!preparedVersion.documents().isEmpty()) {
            elasticsearchService.bulkIndex(preparedVersion.documents());
        }
    }

    public VectorizationUsageResult vectorizeVersion(
            Long fileUploadId,
            int processingVersion,
            String fileMd5,
            String userId,
            String orgTag,
            boolean isPublic,
            String requesterId) {
        PreparedVersion prepared = prepareVersion(
                fileUploadId,
                processingVersion,
                fileMd5,
                userId,
                orgTag,
                isPublic,
                requesterId);
        indexVersion(prepared);
        return prepared.usage();
    }

    private EsDocument toVersionedEsDocument(
            Long fileUploadId,
            int processingVersion,
            String fileMd5,
            String userId,
            String orgTag,
            boolean isPublic,
            DocumentVector chunk,
            float[] vector,
            String modelVersion) {
        EsDocument document = new EsDocument();
        document.setId(fileUploadId + ":" + processingVersion + ":" + chunk.getChunkId());
        document.setFileUploadId(fileUploadId);
        document.setProcessingVersion(processingVersion);
        document.setFileMd5(fileMd5);
        document.setChunkId(chunk.getChunkId());
        document.setTextContent(chunk.getTextContent());
        document.setPageNumber(chunk.getPageNumber());
        document.setAnchorText(chunk.getAnchorText());
        document.setVector(vector);
        document.setModelVersion(modelVersion);
        document.setUserId(userId);
        document.setOrgTag(orgTag);
        document.setPublic(isPublic);
        return document;
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText()
                ))
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }

    public record PreparedVersion(
            List<EsDocument> documents,
            VectorizationUsageResult usage) {
        public PreparedVersion {
            documents = List.copyOf(documents);
        }
    }
}
