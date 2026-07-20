package com.canggo.zhishu.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ProcessingLeaseReaper {

    private static final Logger logger = LoggerFactory.getLogger(ProcessingLeaseReaper.class);

    private final DocumentProcessingTaskService taskService;

    public ProcessingLeaseReaper(DocumentProcessingTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(
            initialDelayString = "${document-processing.reaper.initial-delay-ms:60000}",
            fixedDelayString = "${document-processing.reaper.scan-delay-ms:60000}")
    public void recoverExpiredTasks() {
        int recovered = taskService.recoverExpiredTasks();
        if (recovered > 0) {
            logger.warn("Recovered {} document processing tasks with expired leases", recovered);
        }
    }
}
