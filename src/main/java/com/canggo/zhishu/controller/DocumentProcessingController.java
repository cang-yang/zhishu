package com.canggo.zhishu.controller;

import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.model.DocumentProcessingTask;
import com.canggo.zhishu.model.FileUpload;
import com.canggo.zhishu.repository.DocumentProcessingTaskRepository;
import com.canggo.zhishu.repository.FileUploadRepository;
import com.canggo.zhishu.service.DocumentProcessingRequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/document-processing")
public class DocumentProcessingController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessingController.class);

    private final DocumentProcessingTaskRepository taskRepository;
    private final FileUploadRepository fileRepository;
    private final DocumentProcessingRequestService requestService;

    public DocumentProcessingController(
            DocumentProcessingTaskRepository taskRepository,
            FileUploadRepository fileRepository,
            DocumentProcessingRequestService requestService) {
        this.taskRepository = taskRepository;
        this.fileRepository = fileRepository;
        this.requestService = requestService;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(
            @PathVariable String taskId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        try {
            DocumentProcessingTask task = requireAuthorizedTask(taskId, userId, role);
            return ResponseEntity.ok(success(HttpStatus.OK, "获取文档处理状态成功", taskData(task)));
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("获取文档处理状态失败, taskId={}", taskId, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "获取文档处理状态失败");
        }
    }

    @PostMapping("/{taskId}/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(
            @PathVariable String taskId,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        try {
            requireAuthorizedTask(taskId, userId, role);
            DocumentProcessingRequestService.ProcessingRequestResult result =
                    requestService.reprocessFailed(taskId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", result.taskId());
            data.put("fileUploadId", result.fileUploadId());
            data.put("processingVersion", result.processingVersion());
            data.put("status", result.status().name());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(success(HttpStatus.ACCEPTED, "文档重新处理任务已创建", data));
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("创建文档重新处理任务失败, taskId={}", taskId, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "创建文档重新处理任务失败");
        }
    }

    private DocumentProcessingTask requireAuthorizedTask(String taskId, String userId, String role) {
        DocumentProcessingTask task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new CustomException("处理任务不存在", HttpStatus.NOT_FOUND));
        FileUpload file = fileRepository.findById(task.getFileUploadId())
                .orElseThrow(() -> new CustomException("任务关联文件不存在", HttpStatus.NOT_FOUND));
        if (!"ADMIN".equalsIgnoreCase(role) && !file.getUserId().equals(userId)) {
            throw new CustomException("没有权限访问此文档处理任务", HttpStatus.FORBIDDEN);
        }
        return task;
    }

    private Map<String, Object> taskData(DocumentProcessingTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("fileUploadId", task.getFileUploadId());
        data.put("processingVersion", task.getProcessingVersion());
        data.put("status", task.getStatus().name());
        data.put("currentStage", task.getCurrentStage().name());
        data.put("retryCount", task.getRetryCount());
        data.put("errorMessage", task.getErrorMessage());
        data.put("createdAt", task.getCreatedAt());
        data.put("updatedAt", task.getUpdatedAt());
        data.put("completedAt", task.getCompletedAt());
        return data;
    }

    private Map<String, Object> success(HttpStatus status, String message, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
