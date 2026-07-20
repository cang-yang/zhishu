package com.canggo.zhishu.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.canggo.zhishu.client.EmbeddingClient;
import com.canggo.zhishu.entity.EsDocument;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchActiveVersionTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private FileUploadRepository fileUploadRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OrgTagCacheService orgTagCacheService;

    private HybridSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new HybridSearchService();
        ReflectionTestUtils.setField(searchService, "esClient", esClient);
        ReflectionTestUtils.setField(searchService, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(searchService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(searchService, "userRepository", userRepository);
        ReflectionTestUtils.setField(searchService, "orgTagCacheService", orgTagCacheService);
    }

    @Test
    void scopeContainsActiveFileVersionPairsAndLegacyOwnerPairsOnly() {
        FileUpload active = file(11L, "active-md5", "owner-a", 3);
        FileUpload incomplete = file(12L, "incomplete-md5", "owner-b", null);
        FileUpload legacy = file(13L, "legacy-md5", "actual-legacy-owner", 0);

        Query scope = searchService.buildVisibilityQuery(List.of(active, incomplete, legacy));

        assertTrue(scope.isBool());
        assertEquals(2, scope.bool().should().size());
        Query activePair = scope.bool().should().get(0);
        Query legacyPair = scope.bool().should().get(1);
        assertEquals("1", scope.bool().minimumShouldMatch());
        assertEquals(List.of("fileUploadId", "processingVersion"),
                activePair.bool().must().stream().map(q -> q.term().field()).toList());
        assertEquals(11L, activePair.bool().must().get(0).term().value().longValue());
        assertEquals(3L, activePair.bool().must().get(1).term().value().longValue());
        assertEquals("legacy-md5", legacyPair.bool().must().get(0).term().value().stringValue());
        assertEquals("actual-legacy-owner", legacyPair.bool().must().get(1).term().value().stringValue());
        assertEquals("fileUploadId", legacyPair.bool().mustNot().get(0).exists().field());
    }

    @Test
    void activeFilesWithSameVersionAreGroupedBelowElasticsearchBooleanClauseLimit() {
        List<FileUpload> files = IntStream.range(0, 1500)
                .mapToObj(index -> file((long) index + 1, "md5-" + index, "owner", 3))
                .toList();

        Query scope = searchService.buildVisibilityQuery(files);

        assertTrue(scope.isBool());
        assertEquals(1, scope.bool().should().size());
        Query grouped = scope.bool().should().get(0);
        assertEquals(1500, grouped.bool().must().get(0).terms().terms().value().size());
        assertEquals("fileUploadId", grouped.bool().must().get(0).terms().field());
        assertEquals(3L, grouped.bool().must().get(1).term().value().longValue());
    }

    @Test
    void anonymousHybridAndTextFallbackApplyTheSamePublicVisibilityScope() throws Exception {
        FileUpload active = file(21L, "public-md5", "owner", 2);
        active.setPublic(true);
        when(fileUploadRepository.findByIsPublicTrue()).thenReturn(List.of(active));
        List<SearchRequest> requests = captureSearchRequests();

        when(embeddingClient.embed(any(), eq("system"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{0.2F}));
        searchService.search("hello", 3);

        SearchRequest hybrid = requests.get(0);
        String knnScope = hybrid.knn().get(0).filter().get(0).toString();
        String textScope = hybrid.query().bool().filter().get(0).toString();
        assertEquals(knnScope, textScope);
        assertTrue(knnScope.contains("fileUploadId"));
        assertTrue(knnScope.contains("processingVersion"));

        requests.clear();
        when(embeddingClient.embed(any(), eq("system"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenThrow(new RuntimeException("embedding unavailable"));
        searchService.search("hello", 3);

        String fallbackScope = requests.get(0).query().bool().filter().get(0).toString();
        assertEquals(knnScope, fallbackScope);
    }

    @Test
    void authenticatedHybridAndTextFallbackReuseAccessibleFileScope() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        FileUpload organizationFile = file(31L, "org-md5", "different-owner", 4);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice"))
                .thenReturn(List.of("org-visible"));
        when(fileUploadRepository.findAccessibleFilesWithTags("1", List.of("org-visible")))
                .thenReturn(List.of(organizationFile));
        List<SearchRequest> requests = captureSearchRequests();

        when(embeddingClient.embed(any(), eq("1"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{0.4F}));
        searchService.searchWithPermission("hello", "1", 3);

        String knnScope = requests.get(0).knn().get(0).filter().get(0).toString();
        String textScope = requests.get(0).query().bool().filter().get(0).toString();
        assertEquals(knnScope, textScope);
        assertTrue(knnScope.contains("31"));
        assertTrue(knnScope.contains("4"));

        requests.clear();
        when(embeddingClient.embed(any(), eq("1"), eq(EmbeddingClient.UsageType.QUERY)))
                .thenThrow(new RuntimeException("embedding unavailable"));
        searchService.searchWithPermission("hello", "1", 3);

        assertEquals(knnScope, requests.get(0).query().bool().filter().get(0).toString());
    }

    @Test
    void visibilityDatabaseFailureFailsClosedBeforeElasticsearch() throws Exception {
        when(fileUploadRepository.findByIsPublicTrue()).thenThrow(new RuntimeException("db unavailable"));

        assertTrue(searchService.search("secret", 5).isEmpty());

        verify(esClient, never()).search(any(SearchRequest.class), eq(EsDocument.class));
        verify(embeddingClient, never()).embed(any(), any(), any());
    }

    private List<SearchRequest> captureSearchRequests() throws Exception {
        List<SearchRequest> requests = new ArrayList<>();
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return emptyResponse();
        });
        return requests;
    }

    private SearchResponse<EsDocument> emptyResponse() {
        return SearchResponse.of(response -> response
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).skipped(0).failed(0))
                .hits(hits -> hits
                        .total(total -> total.value(0).relation(TotalHitsRelation.Eq))
                        .hits(List.of())));
    }

    private FileUpload file(Long id, String md5, String owner, Integer activeVersion) {
        FileUpload file = new FileUpload();
        file.setId(id);
        file.setFileMd5(md5);
        file.setFileName(md5 + ".pdf");
        file.setUserId(owner);
        file.setActiveProcessingVersion(activeVersion);
        return file;
    }
}
