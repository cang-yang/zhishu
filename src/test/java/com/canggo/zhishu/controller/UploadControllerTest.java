package com.canggo.zhishu.controller;

import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OrganizationTag;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.service.DocumentProcessingRequestService;
import com.canggo.zhishu.service.FileTypeValidationService;
import com.canggo.zhishu.service.ParseService;
import com.canggo.zhishu.service.UploadService;
import com.canggo.zhishu.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class UploadControllerTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private UserService userService;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private FileTypeValidationService fileTypeValidationService;

    @Mock
    private ParseService parseService;

    @Mock
    private DocumentProcessingRequestService documentProcessingRequestService;

    private UploadController uploadController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadController = new UploadController(uploadService, documentProcessingRequestService);
        ReflectionTestUtils.setField(uploadController, "userService", userService);
        ReflectionTestUtils.setField(uploadController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadController, "fileTypeValidationService", fileTypeValidationService);
        ReflectionTestUtils.setField(uploadController, "parseService", parseService);
        when(fileTypeValidationService.getSupportedFileTypes()).thenReturn(Set.of("pdf"));
    }

    @Test
    void testUploadChunkRejectsOversizedFileForNonAdmin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(1024L * 1024L);

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                2L * 1024 * 1024,
                "test.pdf",
                1,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        assertEquals(413, response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("不超过"));
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void testUploadChunkAllowsAdminToBypassOrgLimit() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(userService.isAdminUser("1")).thenReturn(true);
        when(uploadService.getUploadedChunks("md5", "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "1")).thenReturn(1);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                20L * 1024 * 1024,
                "test.pdf",
                1,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("uploaded", List.of(0), "progress", 100.0d), response.getBody().get("data"));
        verify(uploadService).uploadChunk("md5", 0, 20L * 1024 * 1024, "test.pdf", file, "TEAM_A", false, "1");
        verify(userService, never()).getOrganizationTag(anyString());
    }

    @Test
    void testUploadChunkRejectsWhenLaterChunkExceedsOrgLimitEvenIfTotalSizeIsUnderreported() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());
        OrganizationTag orgTag = new OrganizationTag();
        orgTag.setTagId("TEAM_A");
        orgTag.setUploadMaxSizeBytes(5L * 1024 * 1024L);

        when(userService.isAdminUser("1")).thenReturn(false);
        when(userService.getOrganizationTag("TEAM_A")).thenReturn(orgTag);

        var response = uploadController.uploadChunk(
                "md5",
                1,
                1024L,
                "test.pdf",
                2,
                "TEAM_A",
                false,
                file,
                "1"
        );

        assertEquals(413, response.getStatusCode().value());
        verify(uploadService, never()).uploadChunk(anyString(), anyInt(), anyLong(), anyString(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void mergeReturnsCommittedTaskEvenWhenPostCommitCleanupFails() throws Exception {
        FileUpload file = uploadingFile(false);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(java.util.Optional.of(file));
        when(uploadService.composeOrReuseMergedObject("md5", "test.pdf", "1"))
                .thenReturn("merged/md5");
        when(uploadService.getMergedFileStreamByObjectKey("merged/md5"))
                .thenThrow(new RuntimeException("estimate unavailable"));
        when(documentProcessingRequestService.finalizeUpload(file.getId(), "merged/md5", null, null))
                .thenReturn(new DocumentProcessingRequestService.ProcessingRequestResult(
                        "task-1", file.getId(), 1, com.canggo.zhishu.model.ProcessingTaskStatus.PENDING,
                        "merged/md5"));
        when(uploadService.createPresignedUrl("merged/md5")).thenReturn("https://minio/merged/md5");
        doThrow(new RuntimeException("cleanup failed"))
                .when(uploadService).cleanupUploadedChunks("md5", "1");

        var response = uploadController.mergeFile(new UploadController.MergeRequest("md5", "test.pdf"), "1");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals("task-1", data.get("taskId"));
        assertEquals("PENDING", data.get("status"));
        assertEquals("https://minio/merged/md5", data.get("object_url"));
        var order = inOrder(documentProcessingRequestService, uploadService);
        order.verify(documentProcessingRequestService).finalizeUpload(file.getId(), "merged/md5", null, null);
        order.verify(uploadService).cleanupUploadedChunks("md5", "1");
    }

    @Test
    void rapidUploadCreatesNormalTaskWithoutCallingLegacyCopyStage() throws Exception {
        FileUpload file = uploadingFile(true);
        file.setEstimatedEmbeddingTokens(50L);
        file.setEstimatedChunkCount(3);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(java.util.Optional.of(file));
        when(uploadService.composeOrReuseMergedObject("md5", "test.pdf", "1"))
                .thenReturn("merged/md5");
        when(documentProcessingRequestService.finalizeUpload(file.getId(), "merged/md5", 50L, 3))
                .thenReturn(new DocumentProcessingRequestService.ProcessingRequestResult(
                        "task-rapid", file.getId(), 1, com.canggo.zhishu.model.ProcessingTaskStatus.PENDING,
                        "merged/md5"));
        when(uploadService.createPresignedUrl("merged/md5")).thenReturn("https://minio/merged/md5");

        var response = uploadController.mergeFile(new UploadController.MergeRequest("md5", "test.pdf"), "1");

        assertEquals(200, response.getStatusCode().value());
        verify(uploadService, never()).secondStageInstantUploadDetermination(anyString(), anyString(), anyString());
        verify(documentProcessingRequestService).finalizeUpload(file.getId(), "merged/md5", 50L, 3);
    }

    private FileUpload uploadingFile(boolean rapid) {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("md5");
        file.setFileName("test.pdf");
        file.setUserId("1");
        file.setStatus(0);
        file.setIsRapidUpload(rapid);
        return file;
    }
}
