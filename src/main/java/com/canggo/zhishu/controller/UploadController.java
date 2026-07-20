package com.canggo.zhishu.controller;

import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.OrganizationTag;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.service.DocumentProcessingRequestService;
import com.canggo.zhishu.service.FileTypeValidationService;
import com.canggo.zhishu.service.ParseService;
import com.canggo.zhishu.service.UploadService;
import com.canggo.zhishu.service.UserService;
import com.canggo.zhishu.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private static final long DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024L;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private UserService userService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private FileTypeValidationService fileTypeValidationService;

    @Autowired
    private ParseService parseService;

    private final DocumentProcessingRequestService documentProcessingRequestService;

    public UploadController(
            UploadService uploadService,
            DocumentProcessingRequestService documentProcessingRequestService) {
        this.uploadService = uploadService;
        this.documentProcessingRequestService = documentProcessingRequestService;
    }

    /**
     * 上传文件分片接口
     *
     * @param fileMd5     文件的MD5值，用于唯一标识文件
     * @param chunkIndex  分片索引，表示当前分片的位置
     * @param totalSize   文件总大小
     * @param fileName    文件名
     * @param totalChunks 总分片数量
     * @param orgTag      组织标签，如果未指定则使用用户的主组织标签
     * @param isPublic    是否公开，默认为false
     * @param file        分片文件对象
     * @return 返回包含已上传分片和上传进度的响应
     * @throws IOException 当文件读写发生错误时抛出
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("fileMd5") String fileMd5,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalSize") long totalSize,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
            @RequestParam(value = "orgTag", required = false) String orgTag,
            @RequestParam(value = "isPublic", required = false, defaultValue = "false") boolean isPublic,
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") String userId) throws IOException {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPLOAD_CHUNK");
        try {
            // 文件类型验证（仅在第一个分片时进行验证）
            if (chunkIndex == 0) {
                FileTypeValidationService.FileTypeValidationResult validationResult =
                        fileTypeValidationService.validateFileType(fileName);

                LogUtils.logBusiness("UPLOAD_CHUNK", userId, "文件类型验证结果: fileName=%s, valid=%s, fileType=%s, message=%s",
                        fileName, validationResult.isValid(), validationResult.getFileType(), validationResult.getMessage());

                if (!validationResult.isValid()) {
                    LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "文件类型验证失败: fileName=%s, fileType=%s",
                            new RuntimeException(validationResult.getMessage()), fileName, validationResult.getFileType());
                    monitor.end("文件类型验证失败: " + validationResult.getMessage());

                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.BAD_REQUEST.value());
                    errorResponse.put("message", validationResult.getMessage());
                    errorResponse.put("fileType", validationResult.getFileType());
                    errorResponse.put("supportedTypes", fileTypeValidationService.getSupportedFileTypes());
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
                }
            }

            String fileType = getFileType(fileName);
            String contentType = file.getContentType();

            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "接收到分片上传请求: fileMd5=%s, chunkIndex=%d, fileName=%s, fileType=%s, contentType=%s, fileSize=%d, totalSize=%d, orgTag=%s, isPublic=%s",
                    fileMd5, chunkIndex, fileName, fileType, contentType, file.getSize(), totalSize, orgTag, isPublic);

            // 如果未指定组织标签，则获取用户的主组织标签
            if (orgTag == null || orgTag.isEmpty()) {
                try {
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "组织标签未指定，尝试获取用户主组织标签: fileName=%s", fileName);
                    orgTag = userService.getUserPrimaryOrg(userId);
                    LogUtils.logBusiness("UPLOAD_CHUNK", userId, "成功获取用户主组织标签: fileName=%s, orgTag=%s", fileName, orgTag);
                } catch (Exception e) {
                    LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "获取用户主组织标签失败: fileName=%s", e, fileName);
                    monitor.end("获取主组织标签失败: " + e.getMessage());
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    errorResponse.put("message", "获取用户主组织标签失败: " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                }
            }

            if (!userService.isAdminUser(userId)) {
                OrganizationTag uploadOrg = userService.getOrganizationTag(orgTag);
                Long uploadMaxSizeBytes = uploadOrg.getUploadMaxSizeBytes();
                long estimatedUploadedBytes = (long) chunkIndex * DEFAULT_CHUNK_SIZE_BYTES + file.getSize();
                boolean exceedsLimit = uploadMaxSizeBytes != null
                        && uploadMaxSizeBytes > 0
                        && (totalSize > uploadMaxSizeBytes || estimatedUploadedBytes > uploadMaxSizeBytes);
                if (exceedsLimit) {
                    LogUtils.logUserOperation(userId, "UPLOAD_CHUNK", fileName, "FAILED_SIZE_LIMIT_EXCEEDED");
                    monitor.end("分片上传失败: 文件超过组织上传大小限制");

                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("code", HttpStatus.PAYLOAD_TOO_LARGE.value());
                    errorResponse.put("message", "当前组织限制非管理员上传文件不超过 " + formatSize(uploadMaxSizeBytes)
                            + "，当前文件大小为 " + formatSize(totalSize));
                    errorResponse.put("limitBytes", uploadMaxSizeBytes);
                    errorResponse.put("fileSizeBytes", totalSize);
                    errorResponse.put("orgTag", orgTag);
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorResponse);
                }
            }

            LogUtils.logFileOperation(userId, "UPLOAD_CHUNK", fileName, fileMd5, "PROCESSING");

            //实现秒传功能
            boolean instantUpload = uploadService.instantUploadDetermination(fileMd5, fileName, orgTag, isPublic, userId);
            if (instantUpload) {

                //伪造已完成的分片列表
                int quantity =  (int) Math.ceil((double) totalSize / DEFAULT_CHUNK_SIZE_BYTES);
                List<Integer> uploadedChunks = new ArrayList<>();
                for (int i = 0; i < quantity; i++) {
                    uploadedChunks.add(i);
                }

                // 伪造数据对象
                Map<String, Object> data = new HashMap<>();
                data.put("uploaded", uploadedChunks);
                data.put("progress", 100);

                // 伪造统一响应格式
                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "分片上传成功");
                response.put("data", data);
                return ResponseEntity.ok(response);
            }

            uploadService.uploadChunk(fileMd5, chunkIndex, totalSize, fileName, file, orgTag, isPublic, userId);

            List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);
            int actualTotalChunks = uploadService.getTotalChunks(fileMd5, userId);
            double progress = calculateProgress(uploadedChunks, actualTotalChunks);

            LogUtils.logBusiness("UPLOAD_CHUNK", userId, "分片上传成功: fileMd5=%s, fileName=%s, fileType=%s, chunkIndex=%d, 进度=%.2f%%",
                    fileMd5, fileName, fileType, chunkIndex, progress);
            monitor.end("分片上传成功");

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "分片上传成功");
            response.put("data", data);

            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "分片上传失败: fileMd5=%s, fileName=%s, chunkIndex=%d", e, fileMd5, fileName, chunkIndex);
            monitor.end("分片上传失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", e.getStatus().value());
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(errorResponse);
        } catch (Exception e) {
            String fileType = getFileType(fileName);
            LogUtils.logBusinessError("UPLOAD_CHUNK", userId, "分片上传失败: fileMd5=%s, fileName=%s, fileType=%s, chunkIndex=%d", e, fileMd5, fileName, fileType, chunkIndex);
            monitor.end("分片上传失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "分片上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 获取文件上传状态接口
     *
     * @param fileMd5 文件的MD5值，用于唯一标识文件
     * @return 返回包含已上传分片和上传进度的响应
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUploadStatus(@RequestParam("file_md5") String fileMd5, @RequestAttribute("userId") String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_UPLOAD_STATUS");
        try {
            // 获取文件信息
            String fileName = "unknown";
            String fileType = "unknown";
            try {
                Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
                if (fileUpload.isPresent()) {
                    fileName = fileUpload.get().getFileName();
                    fileType = getFileType(fileName);
                }
            } catch (Exception e) {
                // 获取文件信息失败不影响状态查询，继续处理
                LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "获取文件信息失败，使用默认值: fileMd5=%s, 错误=%s", fileMd5, e.getMessage());
            }

            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "获取文件上传状态: fileMd5=%s, fileName=%s, fileType=%s", fileMd5, fileName, fileType);

            List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);
            int totalChunks = uploadService.getTotalChunks(fileMd5, userId);
            double progress = calculateProgress(uploadedChunks, totalChunks);

            LogUtils.logBusiness("GET_UPLOAD_STATUS", "system", "文件上传状态: fileMd5=%s, fileName=%s, fileType=%s, 已上传=%d/%d, 进度=%.2f%%",
                    fileMd5, fileName, fileType, uploadedChunks.size(), totalChunks, progress);
            monitor.end("获取上传状态成功");

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("uploaded", uploadedChunks);
            data.put("progress", progress);
            data.put("fileName", fileName);
            data.put("fileType", fileType);

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取上传状态成功");
            response.put("data", data);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_UPLOAD_STATUS", "system", "获取文件上传状态失败: fileMd5=%s", e, fileMd5);
            monitor.end("获取上传状态失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "获取上传状态失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 合并文件分片接口
     *
     * @param request 包含文件MD5和文件名的请求体
     * @param userId  当前用户ID
     * @return 返回包含合并后文件访问URL的响应
     */
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergeFile(
            @RequestBody MergeRequest request,
            @RequestAttribute("userId") String userId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("MERGE_FILE");
        try {
            String fileType = getFileType(request.fileName());
            LogUtils.logBusiness("MERGE_FILE", userId, "接收到合并文件请求: fileMd5=%s, fileName=%s, fileType=%s",
                    request.fileMd5(), request.fileName(), fileType);

            // 检查文件完整性和权限
            LogUtils.logBusiness("MERGE_FILE", userId, "检查文件记录和权限: fileMd5=%s, fileName=%s", request.fileMd5(), request.fileName());
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.fileMd5(), userId)
                    .orElseThrow(() -> {
                        LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "FAILED_FILE_NOT_FOUND");
                        return new RuntimeException("文件记录不存在");
                    });

            // 确保用户有权限操作该文件
            if (!fileUpload.getUserId().equals(userId)) {
                LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "FAILED_PERMISSION_DENIED");
                LogUtils.logBusiness("MERGE_FILE", userId, "权限验证失败: 尝试合并不属于自己的文件, fileMd5=%s, fileName=%s, 实际所有者=%s",
                        request.fileMd5(), request.fileName(), fileUpload.getUserId());
                monitor.end("合并失败：权限不足");
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("code", HttpStatus.FORBIDDEN.value());
                errorResponse.put("message", "没有权限操作此文件");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
            }


            String objectKey = uploadService.composeOrReuseMergedObject(
                    request.fileMd5(), request.fileName(), userId);
            LogUtils.logFileOperation(userId, "COMPOSE", request.fileName(), request.fileMd5(), "SUCCESS");

            Long estimatedTokens = null;
            Integer estimatedChunkCount = null;
            if (Boolean.TRUE.equals(fileUpload.getIsRapidUpload())) {
                estimatedTokens = fileUpload.getEstimatedEmbeddingTokens();
                estimatedChunkCount = fileUpload.getEstimatedChunkCount();
            } else {
                try (io.minio.GetObjectResponse mergedFileStream =
                             uploadService.getMergedFileStreamByObjectKey(objectKey)) {
                    ParseService.EmbeddingEstimate embeddingEstimate =
                            parseService.estimateEmbeddingUsage(mergedFileStream);
                    estimatedTokens = embeddingEstimate.estimatedTokens();
                    estimatedChunkCount = embeddingEstimate.estimatedChunkCount();
                } catch (Exception estimateException) {
                    LogUtils.logBusinessError(
                            "MERGE_FILE",
                            userId,
                            "文档 Embedding 预估失败: fileMd5=%s, fileName=%s",
                            estimateException,
                            request.fileMd5(),
                            request.fileName());
                }
            }

            DocumentProcessingRequestService.ProcessingRequestResult processing =
                    documentProcessingRequestService.finalizeUpload(
                            fileUpload.getId(),
                            objectKey,
                            estimatedTokens,
                            estimatedChunkCount);

            try {
                uploadService.cleanupUploadedChunks(request.fileMd5(), userId);
            } catch (Exception cleanupException) {
                LogUtils.logBusiness(
                        "MERGE_FILE",
                        userId,
                        "任务已提交但分片清理未完成，将在重复 merge 时继续清理: fileMd5=%s, error=%s",
                        request.fileMd5(),
                        cleanupException.getMessage());
            }

            String objectUrl = uploadService.createPresignedUrl(objectKey);

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("object_url", objectUrl);
            data.put("taskId", processing.taskId());
            data.put("processingVersion", processing.processingVersion());
            data.put("status", processing.status().name());
            if (estimatedTokens != null) {
                data.put("estimatedEmbeddingTokens", estimatedTokens);
            }
            if (estimatedChunkCount != null) {
                data.put("estimatedChunkCount", estimatedChunkCount);
            }

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件合并成功，处理任务已创建");
            response.put("data", data);

            LogUtils.logUserOperation(userId, "MERGE_FILE", request.fileMd5(), "SUCCESS");
            monitor.end("文件合并成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String fileType = getFileType(request.fileName());
            LogUtils.logBusinessError("MERGE_FILE", userId, "文件合并失败: fileMd5=%s, fileName=%s, fileType=%s", e,
                    request.fileMd5(), request.fileName(), fileType);
            monitor.end("文件合并失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "文件合并失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 计算上传进度
     *
     * @param uploadedChunks 已上传的分片列表
     * @param totalChunks    总分片数量
     * @return 返回上传进度的百分比
     */
    private double calculateProgress(List<Integer> uploadedChunks, int totalChunks) {
        if (totalChunks == 0) {
            LogUtils.logBusiness("CALCULATE_PROGRESS", "system", "计算上传进度时总分片数为0");
            return 0.0;
        }
        return (double) uploadedChunks.size() / totalChunks * 100;
    }

    private String formatSize(long sizeInBytes) {
        double sizeInMb = sizeInBytes / (1024d * 1024d);
        if (sizeInMb >= 1024d) {
            return String.format("%.2f GB", sizeInMb / 1024d);
        }
        if (sizeInMb >= 1d) {
            return String.format("%.2f MB", sizeInMb);
        }
        return String.format("%.2f KB", sizeInBytes / 1024d);
    }

    /**
     * 合并请求的辅助类，包含文件的MD5值和文件名
     */
    public record MergeRequest(String fileMd5, String fileName) {
    }

    /**
     * 获取支持的文件类型列表接口
     *
     * @return 返回支持的文件类型信息
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedFileTypes() {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_SUPPORTED_TYPES");
        try {
            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "获取支持的文件类型列表");

            Set<String> supportedTypes = fileTypeValidationService.getSupportedFileTypes();
            Set<String> supportedExtensions = fileTypeValidationService.getSupportedExtensions();

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("supportedTypes", supportedTypes);
            data.put("supportedExtensions", supportedExtensions);
            data.put("description", "系统支持的文档类型文件，这些文件可以被解析并进行向量化处理");

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取支持的文件类型成功");
            response.put("data", data);

            LogUtils.logBusiness("GET_SUPPORTED_TYPES", "system", "成功返回支持的文件类型: 类型数量=%d, 扩展名数量=%d",
                    supportedTypes.size(), supportedExtensions.size());
            monitor.end("获取支持的文件类型成功");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SUPPORTED_TYPES", "system", "获取支持的文件类型失败", e);
            monitor.end("获取支持的文件类型失败: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("message", "获取支持的文件类型失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 根据文件名获取文件类型
     *
     * @param fileName 文件名
     * @return 文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();

        // 根据文件扩展名返回文件类型
        switch (extension) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "md":
                return "Markdown文档";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "wmv":
                return "WMV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "flac":
                return "FLAC音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            case "tar":
                return "TAR压缩包";
            case "gz":
                return "GZ压缩包";
            case "json":
                return "JSON文件";
            case "xml":
                return "XML文件";
            case "csv":
                return "CSV文件";
            case "html":
            case "htm":
                return "HTML文件";
            case "css":
                return "CSS文件";
            case "js":
                return "JavaScript文件";
            case "java":
                return "Java源码";
            case "py":
                return "Python源码";
            case "cpp":
            case "c":
                return "C/C++源码";
            case "sql":
                return "SQL文件";
            default:
                return extension.toUpperCase() + "文件";
        }
    }
}
