package com.canggo.zhishu.model;

public record DocumentProcessingRequested(
        String eventId,
        String taskId,
        Long fileUploadId,
        int processingVersion,
        String sourceObjectKey) {
}
