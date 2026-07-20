package com.canggo.zhishu.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "document_processing_task",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_processing_task_id", columnNames = "task_id"),
                @UniqueConstraint(
                        name = "uk_processing_file_version",
                        columnNames = {"file_upload_id", "processing_version"})
        },
        indexes = @Index(
                name = "idx_processing_status_lease",
                columnList = "status,lease_expire_at"))
public class DocumentProcessingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(name = "processing_version", nullable = false)
    private Integer processingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProcessingTaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 24)
    private ProcessingStage currentStage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "source_object_key", nullable = false, length = 512)
    private String sourceObjectKey;

    @Column(name = "execution_token", length = 36)
    private String executionToken;

    @Column(name = "lease_expire_at")
    private LocalDateTime leaseExpireAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
