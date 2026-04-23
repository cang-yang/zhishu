package com.canggo.zhishu.service;

import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.repository.DocumentVectorRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    public record PdfSinglePagePreview(byte[] content, boolean cacheHit) {
    }

    private record InMemoryPdfPreviewCache(byte[] content, long expiresAtMillis) {
    }

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final String PDF_SINGLE_PAGE_CACHE_PREFIX = "preview:pdf:single-page:";
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MINUTES = 30;
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(PDF_SINGLE_PAGE_CACHE_TTL_MINUTES);
    private static final Map<String, InMemoryPdfPreviewCache> PDF_SINGLE_PAGE_LOCAL_CACHE = new ConcurrentHashMap<>();

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ParseService parseService;

    @Autowired
    private VectorizationService vectorizationService;

    @Value("${minio.bucketName}")
    private String bucketName;


    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);

        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));

            // 判断文件是否被其他用户使用
            long count = fileUploadRepository.countDistinctUsersByFileMd5(fileMd5);

            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5AndUserId(fileMd5, userId);
                logger.info("成功从Elasticsearch删除文档: {}，用户Id：{}", fileMd5, userId);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}，用户Id：{}", fileMd5, userId, e);
                // 继续删除其他数据
            }

            // 2. 当其他优惠没有这个文件时才删除MinIO中的文件（使用MD5作为对象路径）
            if (count == 1) {
                try {
                    String objectName = "merged/" + fileUpload.getFileMd5();
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .build()
                    );
                    logger.info("成功从MinIO删除文件: {}", objectName);
                } catch (Exception e) {
                    logger.warn("使用MD5路径删除文件失败，尝试使用文件名路径: {}", fileMd5);
                    // 降级：尝试使用旧的文件名路径（兼容旧数据）
                    try {
                        String oldObjectName = "merged/" + fileUpload.getFileName();
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(oldObjectName)
                                        .build()
                        );
                        logger.info("使用旧路径成功从MinIO删除文件: {}", oldObjectName);
                    } catch (Exception ex) {
                        logger.error("从MinIO删除文件时出错（新旧路径都失败）: {}", fileMd5, ex);
                        // 继续删除其他数据
                    }
                }
                //使pdf单页缓存失效
                invalidatePdfSinglePagePreviewCache(fileMd5);
            }


            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5AndUserId(fileMd5, userId);
                logger.info("成功删除文档向量记录: {}，用户Id：{}", fileMd5, userId);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}，用户Id：{}", fileMd5, userId, e);
                // 继续删除其他数据
            }


            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5AndUserId(fileMd5, userId);
            logger.info("成功删除文件上传记录: {}，用户Id：{}", fileMd5, userId);

            logger.info("文档删除完成: {}，用户Id：{}", fileMd5, userId);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}，用户Id：{}", fileMd5, userId, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteDocument(String fileMd5, String ownerUserId, String currentUserId) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new CustomException("文档所属用户不能为空", HttpStatus.BAD_REQUEST);
        }

        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, ownerUserId)
                .orElseThrow(() -> new CustomException("文档不存在", HttpStatus.NOT_FOUND));

        User currentUser = resolveUser(currentUserId);
        if (!isAdmin(currentUser)) {
            String currentUserDbId = String.valueOf(currentUser.getId());
            if (!currentUserDbId.equals(fileUpload.getUserId()) && !currentUserId.equals(fileUpload.getUserId())) {
                throw new CustomException("没有权限修改此文档", HttpStatus.FORBIDDEN);
            }
        }

        deleteDocument(fileMd5, fileUpload.getUserId());
    }

    @Transactional
    public VectorizationService.VectorizationUsageResult reindexDocument(String fileMd5, String requesterId) {
        logger.info("开始重建文档索引: fileMd5={}, requesterId={}", fileMd5, requesterId);

        List<FileUpload> fileUploads = fileUploadRepository
                .findAllByFileMd5AndStatusOrderByCreatedAtAsc(fileMd5, 1);


        if (fileUploads.isEmpty()) {
            throw new RuntimeException("文件不存在");
        }
        FileUpload fileUpload = fileUploads.get(0);
        try (InputStream fileStream = uploadService.getMergedFileStream(fileMd5)) {
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("重建前已清理 Elasticsearch 文档: {}", fileMd5);
            } catch (Exception e) {
                logger.warn("重建前清理 Elasticsearch 失败: fileMd5={}, error={}", fileMd5, e.getMessage());
            }
            //删除数据库中的分块数据
            documentVectorRepository.deleteByFileMd5(fileMd5);
            //删除缓存的预览页面
            invalidatePdfSinglePagePreviewCache(fileMd5);
            //以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
            parseService.parseAndSave(
                    fileMd5,
                    fileStream,
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic()
            );
            //向量化文档并保存到es
            VectorizationService.VectorizationUsageResult result = vectorizationService.vectorizeWithUsage(
                    fileMd5,
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic(),
                    requesterId
            );

            fileUpload.setActualEmbeddingTokens((long) result.actualEmbeddingTokens());
            fileUpload.setActualChunkCount(result.actualChunkCount());
            fileUploadRepository.save(fileUpload);
            //循环存储其他用户的文档
            for (int i = 1; i < fileUploads.size(); i++) {
                FileUpload fileUpload0 = fileUploads.get(i);

                //保存内容块到数据库
                uploadService.saveContentBlock(fileMd5, fileUpload.getUserId(), fileUpload0);

                //保存向量内容到ES
                uploadService.saveVectorData(fileMd5, fileUpload.getUserId(), fileUpload0);

            }

            logger.info(
                    "文档索引重建完成: fileMd5={}, actualTokens={}, actualChunkCount={}",
                    fileMd5,
                    result.actualEmbeddingTokens(),
                    result.actualChunkCount()
            );
            return result;
        } catch (TikaException e) {
            logger.error("重建文档索引失败，文档解析异常: {}", fileMd5, e);
            throw new RuntimeException("重建文档索引失败: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("重建文档索引失败: {}", fileMd5, e);
            throw new RuntimeException("重建文档索引失败: " + e.getMessage(), e);
        }
    }

    public boolean canManageDocument(FileUpload file, String requesterId, String role) {
        if (file == null || requesterId == null || requesterId.isBlank()) {
            return false;
        }

        if ("ADMIN".equalsIgnoreCase(role)) {
            return true;
        }

        User requester = resolveUser(requesterId);
        if (requesterId.equals(String.valueOf(file.getUserId())) || String.valueOf(requester.getId()).equals(file.getUserId())) {
            return true;
        }
        return false;
    }

    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId  用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);

        try {
            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());

            if (isAdmin(user)) {
                List<FileUpload> files = fileUploadRepository.findAll();
                logger.debug("管理员访问，返回全部文件记录: fileCount={}", files.size());
                return files;
            }

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);

            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }

            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);

        try {
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        }
    }

    private boolean isAdmin(User user) {
        return user != null && User.Role.ADMIN.equals(user.getRole());
    }

    private List<String> getOwnedOrgTags(User user) {
        List<String> cachedOrgTags = orgTagCacheService.getUserOrgTags(user.getUsername());
        if (cachedOrgTags != null && !cachedOrgTags.isEmpty()) {
            return cachedOrgTags;
        }

        if (user.getOrgTags() == null || user.getOrgTags().isBlank()) {
            return List.of();
        }

        return Arrays.stream(user.getOrgTags().split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    /**
     * 生成文件下载链接
     *
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5, String originalFileName) {
        logger.info("生成文件下载链接: fileMd5={}, originalFileName={}", fileMd5, originalFileName);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            String effectiveFileName = (originalFileName != null && !originalFileName.isBlank())
                    ? originalFileName
                    : fileUpload.getFileName();
            Map<String, String> reqParams = buildContentDispositionQueryParams(effectiveFileName, true);

            // 优先使用新的MD5路径
            String objectName = "merged/" + fileMd5;

            try {
                // 尝试使用新路径（MD5）
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(objectName)
                                .expiry(1, TimeUnit.HOURS)
                                .extraQueryParams(reqParams)
                                .build()
                );
                logger.info("成功生成文件下载链接（新路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), objectName);

                // 使用 publicUrl 公开域名来替换原始域名
                presignedUrl = uploadService.transToPublicUrl(presignedUrl);
                return presignedUrl;
            } catch (Exception e) {
                logger.warn("使用新路径生成下载链接失败，尝试使用旧路径（文件名）: fileMd5={}", fileMd5);
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                String oldObjectName = "merged/" + fileUpload.getFileName();
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(oldObjectName)
                                .expiry(1, TimeUnit.HOURS)
                                .extraQueryParams(reqParams)
                                .build()
                );
                logger.info("成功生成文件下载链接（旧路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), oldObjectName);
                presignedUrl = uploadService.transToPublicUrl(presignedUrl);
                return presignedUrl;
            }
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    public String generatePreviewUrl(String fileMd5, String originalFileName) {
        logger.info("生成文件预览链接: fileMd5={}, originalFileName={}", fileMd5, originalFileName);

        try {
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            String effectiveFileName = (originalFileName != null && !originalFileName.isBlank())
                    ? originalFileName
                    : fileUpload.getFileName();
            Map<String, String> reqParams = buildContentDispositionQueryParams(effectiveFileName, false);

            String objectName = "merged/" + fileMd5;

            try {
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(objectName)
                                .expiry(1, TimeUnit.HOURS)
                                .extraQueryParams(reqParams)
                                .build()
                );
                return uploadService.transToPublicUrl(presignedUrl);
            } catch (Exception e) {
                String oldObjectName = "merged/" + fileUpload.getFileName();
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(oldObjectName)
                                .expiry(1, TimeUnit.HOURS)
                                .extraQueryParams(reqParams)
                                .build()
                );
                return uploadService.transToPublicUrl(presignedUrl);
            }
        } catch (Exception e) {
            logger.error("生成文件预览链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    private Map<String, String> buildContentDispositionQueryParams(String originalFileName, boolean attachment) {
        Map<String, String> reqParams = new HashMap<>();
        if (originalFileName == null || originalFileName.isBlank()) {
            return reqParams;
        }

        String encodedFileName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8).replace("+", "%20");
        String dispositionType = attachment ? "attachment" : "inline";
        reqParams.put(
                "response-content-disposition",
                dispositionType + "; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName
        );
        return reqParams;
    }

    /**
     * 获取文件预览内容
     *
     * @param fileMd5  文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于文本文件返回前几KB内容，非文本文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);

            if (isTextFile) {
                // 对于文本文件，读取前10KB内容
                try (InputStream inputStream = openFileStream(fileUpload);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes("UTF-8").length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    logger.info("成功获取文本文件预览内容: fileMd5={}, contentLength={}, 内容前50字符={}",
                            fileMd5, result.length(), result.substring(0, Math.min(50, result.length())));
                    return result;
                }
            } else {
                // 对于非文本文件，返回文件信息
                String fileInfo = String.format(
                        "文件名: %s\n" +
                                "文件大小: %s\n" +
                                "文件类型: %s\n" +
                                "上传时间: %s\n\n" +
                                "此文件类型不支持预览，请下载后查看。",
                        fileName,
                        formatFileSize(fileUpload.getTotalSize()),
                        fileExtension.toUpperCase(),
                        fileUpload.getCreatedAt()
                );

                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }

    public PdfSinglePagePreview getPdfSinglePagePreview(String fileMd5, int pageNumber) {
        logger.info("生成 PDF 单页预览: fileMd5={}, pageNumber={}", fileMd5, pageNumber);

        try {
            //构建单页PDF缓存键
            String cacheKey = buildPdfSinglePageCacheKey(fileMd5, pageNumber);
            //获取本地PDF单页预览
            byte[] localPreview = getLocalPdfSinglePagePreview(cacheKey);
            if (localPreview != null) {
                logger.info("命中 PDF 单页预览本地缓存: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, localPreview.length);
                return new PdfSinglePagePreview(localPreview, true);
            }
            //获取缓存的PDF单页预览
            byte[] cachedPreview = getCachedPdfSinglePagePreview(cacheKey);
            if (cachedPreview != null) {
                //缓存本地PDF单页预览
                cacheLocalPdfSinglePagePreview(cacheKey, cachedPreview);
                logger.info("命中 PDF 单页预览缓存: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, cachedPreview.length);
                //返回PDF 单页预览
                return new PdfSinglePagePreview(cachedPreview, true);
            }

            // 1. 查库：根据 MD5 找最新的文件记录，找不到就抛异常
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 2. 准备资源：使用 try-with-resources 确保流和文档能被正确关闭，防止内存泄漏
            try (InputStream inputStream = openFileStream(fileUpload); // 打开源文件流
                 PDDocument sourceDocument = PDDocument.load(inputStream); // 加载源 PDF
                 PDDocument singlePageDocument = new PDDocument(); // 创建一个全新的空 PDF 用于存放单页
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) { // 准备内存输出流

                // 3. 校验页码：获取总页数，判断传入的 pageNumber (通常是 1-based) 是否合法
                int totalPages = sourceDocument.getNumberOfPages();
                if (pageNumber < 1 || pageNumber > totalPages) {
                    throw new IllegalArgumentException("页码超出范围: " + pageNumber + "/" + totalPages);
                }

                // 4. 提取与保存：将源 PDF 的指定页（0-based，所以减1）导入到新的空 PDF 中
                singlePageDocument.importPage(sourceDocument.getPage(pageNumber - 1));
                singlePageDocument.save(outputStream); // 将新 PDF 写入内存流

                // 5. 缓存与返回：获取字节数组，执行双写缓存（本地+可能存在的远程缓存如Redis），打印日志并返回
                byte[] previewBytes = outputStream.toByteArray();
                cacheLocalPdfSinglePagePreview(cacheKey, previewBytes);
                cachePdfSinglePagePreview(cacheKey, previewBytes);
                logger.info("成功生成 PDF 单页预览: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, outputStream.size());
                return new PdfSinglePagePreview(previewBytes, false);
            }
        } catch (Exception e) {
            logger.error("生成 PDF 单页预览失败: fileMd5={}, pageNumber={}", fileMd5, pageNumber, e);
            throw new RuntimeException("生成 PDF 单页预览失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    private InputStream openFileStream(FileUpload fileUpload) throws Exception {
        String objectName = "merged/" + fileUpload.getFileMd5();

        try {
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
            logger.info("使用新路径（MD5）获取文件流: fileMd5={}, objectName={}", fileUpload.getFileMd5(), objectName);
            return inputStream;
        } catch (Exception e) {
            logger.warn("使用新路径获取文件失败，尝试使用旧路径（文件名）: fileMd5={}, error={}",
                    fileUpload.getFileMd5(), e.getMessage());
            String oldObjectName = "merged/" + fileUpload.getFileName();
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(oldObjectName)
                            .build());
            logger.info("使用旧路径（文件名）获取文件流: fileMd5={}, objectName={}", fileUpload.getFileMd5(), oldObjectName);
            return inputStream;
        }
    }

    private String buildPdfSinglePageCacheKey(String fileMd5, int pageNumber) {
        return PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":" + pageNumber;
    }


    /**
     * 从本地内存缓存中获取 PDF 单页预览内容
     *
     * @param cacheKey 缓存键，用于唯一标识 PDF 页面
     * @return PDF 页面的字节数组，如果缓存不存在或已过期则返回 null
     */
    private byte[] getLocalPdfSinglePagePreview(String cacheKey) {
        InMemoryPdfPreviewCache cached = PDF_SINGLE_PAGE_LOCAL_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }

        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            PDF_SINGLE_PAGE_LOCAL_CACHE.remove(cacheKey);
            return null;
        }

        return cached.content();
    }


    private void cacheLocalPdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        PDF_SINGLE_PAGE_LOCAL_CACHE.put(
                cacheKey,
                new InMemoryPdfPreviewCache(previewBytes, System.currentTimeMillis() + PDF_SINGLE_PAGE_CACHE_TTL_MILLIS)
        );
    }

    private byte[] getCachedPdfSinglePagePreview(String cacheKey) {
        try {
            String normalizedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (normalizedValue != null && !normalizedValue.isBlank()) {
                normalizedValue = normalizedValue.trim();
                if (normalizedValue.startsWith("\"") && normalizedValue.endsWith("\"") && normalizedValue.length() >= 2) {
                    normalizedValue = normalizedValue.substring(1, normalizedValue.length() - 1);
                }
                logger.info("命中 PDF 单页预览缓存 key: cacheKey={}, encodedLength={}", cacheKey, normalizedValue.length());
                return Base64.getDecoder().decode(normalizedValue);
            }
            logger.info("未命中 PDF 单页预览缓存 key: cacheKey={}", cacheKey);
        } catch (Exception e) {
            logger.warn("读取 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
        return null;
    }

    private void cachePdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        try {
            String encodedPreview = Base64.getEncoder().encodeToString(previewBytes);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    encodedPreview,
                    PDF_SINGLE_PAGE_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            String storedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            Long ttlSeconds = stringRedisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            logger.info("写入 PDF 单页预览缓存完成: cacheKey={}, encodedLength={}, storedLength={}, ttlSeconds={}",
                    cacheKey,
                    encodedPreview.length(),
                    storedValue != null ? storedValue.length() : 0,
                    ttlSeconds);
        } catch (Exception e) {
            logger.warn("写入 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
    }

    private void invalidatePdfSinglePagePreviewCache(String fileMd5) {
        try {
            PDF_SINGLE_PAGE_LOCAL_CACHE.keySet().removeIf(key -> key.startsWith(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":"));
            Set<String> cacheKeys = stringRedisTemplate.keys(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":*");
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                stringRedisTemplate.delete(cacheKeys);
                logger.info("删除 PDF 单页预览缓存: fileMd5={}, cacheCount={}", fileMd5, cacheKeys.size());
            }
        } catch (Exception e) {
            logger.warn("删除 PDF 单页预览缓存失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }

    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {
                "txt", "md", "html", "htm", "xml", "json",
                "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css",
                "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"
        };

        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";

        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 
