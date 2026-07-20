package com.canggo.zhishu.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VersionedChunkWriter {

    private static final String INSERT_SQL = """
            INSERT INTO document_vectors (
                file_upload_id, processing_version, file_md5, chunk_id, content_hash,
                text_content, page_number, anchor_text, user_id, org_tag, is_public)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public VersionedChunkWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(
            Long fileUploadId,
            int processingVersion,
            String fileMd5,
            int chunkId,
            String contentHash,
            String textContent,
            Integer pageNumber,
            String anchorText,
            String userId,
            String orgTag,
            boolean isPublic) {
        try {
            return jdbcTemplate.update(
                    INSERT_SQL,
                    fileUploadId,
                    processingVersion,
                    fileMd5,
                    chunkId,
                    contentHash,
                    textContent,
                    pageNumber,
                    anchorText,
                    userId,
                    orgTag,
                    isPublic) == 1;
        } catch (DuplicateKeyException duplicateBusinessKey) {
            return false;
        }
    }
}
