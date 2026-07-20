package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.FileUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FileUpload f WHERE f.id = :id")
    Optional<FileUpload> findByIdForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE file_upload
               SET active_processing_version = :processingVersion,
                   actual_embedding_tokens = :actualEmbeddingTokens,
                   actual_chunk_count = :actualChunkCount
             WHERE id = :fileUploadId
               AND latest_processing_version = :processingVersion
            """, nativeQuery = true)
    int activateVersionIfLatest(
            @Param("fileUploadId") Long fileUploadId,
            @Param("processingVersion") int processingVersion,
            @Param("actualEmbeddingTokens") long actualEmbeddingTokens,
            @Param("actualChunkCount") int actualChunkCount);

    List<FileUpload> findByIsPublicTrue();

    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    Optional<FileUpload> findFirstByFileMd5(String fileMd5);


    List<FileUpload> findAllByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(String fileMd5);

    Optional<FileUpload> findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(String fileName);
    
    long countByFileMd5(String fileMd5);

    List<FileUpload> findAllByFileMd5(String fileMd5);

    // 查询相同的 md5 文件有多少个不同的用户
    @Query("SELECT COUNT(DISTINCT f.userId) FROM FileUpload f WHERE f.fileMd5 = :fileMd5")
    long countDistinctUsersByFileMd5(@Param("fileMd5") String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);

    long countByOrgTag(String orgTag);
    
    void deleteByFileMd5(String fileMd5);

    
    void deleteByFileMd5AndUserId(String fileMd5, String userId);

    /**
     * 查询用户自己的文件和公开文件
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);
    
    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 公开的文件
     *      3. 用户所属组织的文件（包含层级关系）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);



    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByUserIdAndFileNameOrderByCreatedAtDesc(String userId, String fileName);

    List<FileUpload> findByFileMd5In(List<String> md5List);
    @Query("SELECT f FROM FileUpload f WHERE f.fileMd5 = :fileMd5 AND f.status = :status ORDER BY f.createdAt ASC")
    Optional<FileUpload> findFirstByFileMd5AndStatusOrderByCreatedAtAsc(String fileMd5, int status);

    List<FileUpload> findAllByFileMd5AndStatusOrderByCreatedAtAsc(String fileMd5, int i);
}
