package com.canggo.zhishu.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.canggo.zhishu.client.EmbeddingClient;
import com.canggo.zhishu.entity.EsDocument;
import com.canggo.zhishu.entity.SearchResult;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.repository.UserRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.FieldValue;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);

        final List<String> userEffectiveTags;
        final String userDbId;
        final Query visibilityQuery;
        try {
            // 获取用户有效的组织标签（包含层级关系）
            userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            List<FileUpload> accessibleFiles = userEffectiveTags.isEmpty()
                    ? fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId)
                    : fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
            visibilityQuery = buildVisibilityQuery(accessibleFiles);
            if (visibilityQuery.isMatchNone()) {
                return Collections.emptyList();
            }
        } catch (Exception visibilityFailure) {
            logger.error("Failed to build active-version visibility scope for user {}", userId, visibilityFailure);
            return Collections.emptyList();
        }

        try {
            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, userId);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, visibilityQuery, topK);
            }

            logger.debug("向量生成成功，开始执行混合搜索 KNN");

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                // KNN 召回窗口
                int recallK = topK * 30;

                // ===== KNN 向量召回 + 权限过滤 =====
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK * 2) // 候选数设为 k 的 2 倍，提升召回精度
                        // KNN 也必须做权限过滤，否则无权限文档会通过并集合并混入结果
                        .filter(visibilityQuery)
                );

                // ===== 关键词匹配 + 权限过滤 =====
                s.query(q -> q.bool(b -> b
                        .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                        .filter(visibilityQuery)
                ));

                // ===== 第二阶段 BM25 rescore =====
                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)
                                ))
                        )
                );
                s.size(topK);
                return s;
            }, EsDocument.class);

            logger.debug("Elasticsearch查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "HYBRID",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();

            logger.debug("返回搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, visibilityQuery, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(
            String query,
            Query visibilityQuery,
            int topK) {
        try {
            logger.debug("开始执行带 active-version 门禁的纯文本搜索");

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    .filter(visibilityQuery)
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "TEXT_ONLY",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        final Query visibilityQuery;
        try {
            visibilityQuery = buildVisibilityQuery(fileUploadRepository.findByIsPublicTrue());
            if (visibilityQuery.isMatchNone()) {
                return Collections.emptyList();
            }
        } catch (Exception visibilityFailure) {
            logger.error("Failed to build anonymous public active-version visibility scope", visibilityFailure);
            return Collections.emptyList();
        }

        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.debug("使用匿名公开文档 active-version 门禁");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, "system");
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, visibilityQuery, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        int recallK = topK * 30;
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                                .filter(visibilityQuery)
                        );

                        // 过滤仅保留包含关键词的文本
                        s.query(q -> q.bool(b -> b
                                .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                .filter(visibilityQuery)));

                        // rescore BM25
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                null,
                                null,
                                false,
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "HYBRID",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, visibilityQuery, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(
            String query,
            Query visibilityQuery,
            int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(ma -> ma
                                .field("textContent")
                                .query(query)))
                        .filter(visibilityQuery)))
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            null,
                            null,
                            false,
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            "TEXT_ONLY",
                            hit.source().getTextContent()
                    );
                })
                .toList();
    }

    Query buildVisibilityQuery(List<FileUpload> accessibleFiles) {
        if (accessibleFiles == null || accessibleFiles.isEmpty()) {
            return Query.of(query -> query.matchNone(matchNone -> matchNone));
        }

        Map<Integer, LinkedHashSet<Long>> activeFileIdsByVersion = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> legacyMd5sByOwner = new LinkedHashMap<>();
        for (FileUpload file : accessibleFiles) {
            Integer activeVersion = file.getActiveProcessingVersion();
            if (activeVersion == null) {
                continue;
            }
            if (activeVersion > 0 && file.getId() != null) {
                activeFileIdsByVersion
                        .computeIfAbsent(activeVersion, ignored -> new LinkedHashSet<>())
                        .add(file.getId());
                continue;
            }
            if (activeVersion == 0
                    && file.getFileMd5() != null
                    && file.getUserId() != null) {
                legacyMd5sByOwner
                        .computeIfAbsent(file.getUserId(), ignored -> new LinkedHashSet<>())
                        .add(file.getFileMd5());
            }
        }

        List<Query> branches = new ArrayList<>();
        activeFileIdsByVersion.forEach((version, fileIds) -> {
            Query fileScope = numericTermOrTerms("fileUploadId", fileIds);
            branches.add(Query.of(query -> query.bool(bool -> bool
                    .must(fileScope)
                    .must(must -> must.term(term -> term
                            .field("processingVersion")
                            .value(version))))));
        });
        legacyMd5sByOwner.forEach((ownerUserId, fileMd5s) -> {
            Query fileScope = stringTermOrTerms("fileMd5", fileMd5s);
            branches.add(Query.of(query -> query.bool(bool -> bool
                    .must(fileScope)
                    .must(must -> must.term(term -> term
                            .field("userId")
                            .value(ownerUserId)))
                    .mustNot(mustNot -> mustNot.exists(exists -> exists
                            .field("fileUploadId"))))));
        });

        if (branches.isEmpty()) {
            return Query.of(query -> query.matchNone(matchNone -> matchNone));
        }
        return Query.of(query -> query.bool(bool -> {
            branches.forEach(bool::should);
            return bool.minimumShouldMatch("1");
        }));
    }

    private Query numericTermOrTerms(String field, LinkedHashSet<Long> values) {
        if (values.size() == 1) {
            return Query.of(query -> query.term(term -> term
                    .field(field)
                    .value(values.iterator().next())));
        }
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(value -> value.value(fieldValues))));
    }

    private Query stringTermOrTerms(String field, LinkedHashSet<String> values) {
        if (values.size() == 1) {
            return Query.of(query -> query.term(term -> term
                    .field(field)
                    .value(values.iterator().next())));
        }
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(value -> value.value(fieldValues))));
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
