package com.canggo.zhishu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.canggo.zhishu.entity.SearchResult;
import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.exception.RateLimitExceededException;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatMessageRecord;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatSessionMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final int MAX_CONTEXT_SNIPPET_LEN = 300;
    private static final int MAX_MATCHED_CHUNK_LEN = 800;
    private static final int MAX_EVIDENCE_SNIPPET_LEN = 160;

    private final RedisChatSessionStore chatSessionStore;
    private final HybridSearchService searchService;
    private final LlmProviderRouter llmProviderRouter;
    private final RateLimitService rateLimitService;
    private final ThreadPoolTaskExecutor chatMonitorExecutor;
    private final ObjectMapper objectMapper;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ReferenceInfo>> sessionReferenceMappings = new ConcurrentHashMap<>();
    private final Map<String, String> requestAssistantMessageIds = new ConcurrentHashMap<>();
    private final Map<String, String> requestSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> requestWsSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> activeRequestBySessionId = new ConcurrentHashMap<>();
    private final Map<String, String> activeRequestByWsSessionId = new ConcurrentHashMap<>();

    public ChatHandler(RedisChatSessionStore chatSessionStore,
                       HybridSearchService searchService,
                       LlmProviderRouter llmProviderRouter,
                       RateLimitService rateLimitService,
                       @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor chatMonitorExecutor,
                       ObjectMapper objectMapper) {
        this.chatSessionStore = chatSessionStore;
        this.searchService = searchService;
        this.llmProviderRouter = llmProviderRouter;
        this.rateLimitService = rateLimitService;
        this.chatMonitorExecutor = chatMonitorExecutor;
        this.objectMapper = objectMapper;
    }

    public ChatSessionMeta createSession(String userId) {
        return chatSessionStore.createSession(userId);
    }

    public List<ChatSessionMeta> listSessions(String userId) {
        chatSessionStore.migrateLegacyConversationIfNeeded(userId);
        return chatSessionStore.listSessions(userId);
    }

    public List<ChatMessageRecord> listMessages(String userId, String sessionId) {
        chatSessionStore.migrateLegacyConversationIfNeeded(userId);
        chatSessionStore.requireOwnedSession(userId, sessionId);
        return chatSessionStore.listMessages(sessionId, false);
    }

    public ChatSessionMeta getSessionMeta(String userId, String sessionId) {
        chatSessionStore.migrateLegacyConversationIfNeeded(userId);
        return chatSessionStore.requireOwnedSession(userId, sessionId);
    }

    public ChatSessionMeta renameSession(String userId, String sessionId, String title) {
        return chatSessionStore.renameSession(userId, sessionId, title);
    }

    public void deleteSession(String userId, String sessionId) {
        chatSessionStore.deleteSession(userId, sessionId);
    }

    public ChatMessageRecord deleteMessage(String userId, String messageId) {
        return chatSessionStore.deleteMessage(userId, messageId);
    }

    public ChatMessageRecord getMessage(String userId, String messageId) {
        return chatSessionStore.requireOwnedMessage(userId, messageId);
    }

    public ChatMessageRecord ensureRegeneratable(String userId, String messageId) {
        ChatMessageRecord message = chatSessionStore.requireOwnedMessage(userId, messageId);
        if (!"assistant".equals(message.role())) {
            throw new CustomException("仅支持重生成 AI 回复", HttpStatus.BAD_REQUEST);
        }
        if (!message.regeneratable()) {
            throw new CustomException("仅允许重生成最后一条 AI 回复", HttpStatus.BAD_REQUEST);
        }
        return message;
    }

    public void processLegacyMessage(String userId, String userMessage, WebSocketSession session) {
        String sessionId = resolveDefaultSession(userId).sessionId();
        processMessage(userId, sessionId, session.getId() + "-legacy-" + System.currentTimeMillis(), userMessage, session);
    }

    public void processMessage(String userId, String sessionId, String requestId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 业务会话ID: {}, requestId: {}", userId, sessionId, requestId);
        try {
            rateLimitService.checkChatByUser(userId);

            ChatSessionMeta targetSession = resolveTargetSession(userId, sessionId);
            ensureSessionNotBusy(targetSession.sessionId());

            List<Map<String, String>> history = buildHistoryBeforeCurrentQuestion(userId, targetSession.sessionId());
            ChatMessageRecord userRecord = chatSessionStore.createUserMessage(userId, targetSession.sessionId(), userMessage);
            ChatMessageRecord assistantRecord = chatSessionStore.createAssistantPlaceholder(userId, targetSession.sessionId());
            ChatSessionMeta latestSessionMeta = chatSessionStore.getSessionMeta(targetSession.sessionId());

            responseBuilders.put(requestId, new StringBuilder());
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(requestId, responseFuture);
            requestAssistantMessageIds.put(requestId, assistantRecord.messageId());
            requestSessionIds.put(requestId, targetSession.sessionId());
            requestWsSessionIds.put(requestId, session.getId());
            activeRequestBySessionId.put(targetSession.sessionId(), requestId);
            activeRequestByWsSessionId.put(session.getId(), requestId);

            sendAcceptedEvent(session, requestId, latestSessionMeta, userRecord, assistantRecord);

            List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);
            String context = buildContext(searchResults, assistantRecord.messageId(), targetSession.sessionId(), session.getId(), userMessage);

            llmProviderRouter.streamResponse(userId, userMessage, context, history,
                    chunk -> handleChunk(requestId, chunk, session, userId),
                    error -> handleStreamError(requestId, userId, session, error));

            submitCompletionMonitor(requestId, userId, session);
        } catch (RateLimitExceededException exception) {
            sendRateLimitMessage(session, exception);
        } catch (Exception exception) {
            logger.error("处理消息错误: {}", exception.getMessage(), exception);
            sendErrorEvent(session, requestId, sessionId, null, exception.getMessage());
        }
    }

    public void regenerateMessage(String userId, String sessionId, String messageId, String requestId, WebSocketSession session) {
        try {
            chatSessionStore.requireOwnedSession(userId, sessionId);
            ensureSessionNotBusy(sessionId);

            ChatMessageRecord assistantRecord = chatSessionStore.regenerateAssistantMessage(userId, messageId);
            String prompt = findPromptForAssistant(userId, sessionId, assistantRecord.seq());
            List<Map<String, String>> history = buildHistoryBeforeAssistantPrompt(userId, sessionId, assistantRecord.seq());
            ChatSessionMeta latestSessionMeta = chatSessionStore.getSessionMeta(sessionId);

            responseBuilders.put(requestId, new StringBuilder());
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(requestId, responseFuture);
            requestAssistantMessageIds.put(requestId, assistantRecord.messageId());
            requestSessionIds.put(requestId, sessionId);
            requestWsSessionIds.put(requestId, session.getId());
            activeRequestBySessionId.put(sessionId, requestId);
            activeRequestByWsSessionId.put(session.getId(), requestId);

            sendAcceptedEvent(session, requestId, latestSessionMeta, null, assistantRecord);

            List<SearchResult> searchResults = searchService.searchWithPermission(prompt, userId, 5);
            String context = buildContext(searchResults, assistantRecord.messageId(), sessionId, session.getId(), prompt);

            llmProviderRouter.streamResponse(userId, prompt, context, history,
                    chunk -> handleChunk(requestId, chunk, session, userId),
                    error -> handleStreamError(requestId, userId, session, error));

            submitCompletionMonitor(requestId, userId, session);
        } catch (Exception exception) {
            logger.error("重生成消息失败: {}", exception.getMessage(), exception);
            sendErrorEvent(session, requestId, sessionId, messageId, exception.getMessage());
        }
    }

    public void stopResponse(String userId, String sessionId, String requestId, String messageId, WebSocketSession session) {
        String targetRequestId = resolveRequestId(requestId, sessionId, messageId, session.getId());
        if (targetRequestId == null) {
            sendStoppedEvent(session, null, sessionId, messageId);
            return;
        }

        logger.info("收到停止请求，用户ID: {}, requestId: {}", userId, targetRequestId);
        stopFlags.put(targetRequestId, true);
        sendStoppedEvent(session, targetRequestId, requestSessionIds.get(targetRequestId), requestAssistantMessageIds.get(targetRequestId));
    }

    public String getReferenceMd5(String sessionId, int referenceNumber) {
        ReferenceInfo detail = getReferenceDetail(sessionId, referenceNumber);
        return detail != null ? detail.fileMd5() : null;
    }

    public ReferenceInfo getReferenceDetail(String sessionId, int referenceNumber) {
        Map<Integer, ReferenceInfo> inMemoryMappings = sessionReferenceMappings.get(sessionId);
        if (inMemoryMappings != null && inMemoryMappings.containsKey(referenceNumber)) {
            return inMemoryMappings.get(referenceNumber);
        }

        ChatSessionMeta meta = chatSessionStore.getSessionMeta(sessionId);
        if (meta != null && meta.lastAssistantMessageId() != null) {
            ChatMessageRecord assistantMessage = chatSessionStore.getMessage(meta.lastAssistantMessageId());
            if (assistantMessage != null && assistantMessage.referenceMappings() != null) {
                return fromPersistedReference(assistantMessage.referenceMappings().get(referenceNumber));
            }
        }
        return null;
    }

    public void clearSessionReferenceMapping(String sessionId) {
        sessionReferenceMappings.remove(sessionId);
        String requestId = activeRequestByWsSessionId.remove(sessionId);
        if (requestId != null) {
            stopFlags.put(requestId, true);
        }
    }

    private void handleChunk(String requestId, String chunk, WebSocketSession session, String userId) {
        if (Boolean.TRUE.equals(stopFlags.get(requestId))) {
            return;
        }

        StringBuilder responseBuilder = responseBuilders.get(requestId);
        if (responseBuilder != null) {
            responseBuilder.append(chunk);
        }

        String messageId = requestAssistantMessageIds.get(requestId);
        String sessionId = requestSessionIds.get(requestId);
        if (messageId == null || sessionId == null) {
            return;
        }

        chatSessionStore.appendAssistantChunk(userId, messageId, chunk);
        sendChunkEvent(session, requestId, sessionId, messageId, chunk);
    }

    private void handleStreamError(String requestId, String userId, WebSocketSession session, Throwable error) {
        logger.error("AI 服务错误: {}", error.getMessage(), error);
        String messageId = requestAssistantMessageIds.get(requestId);
        String sessionId = requestSessionIds.get(requestId);

        if (messageId != null) {
            chatSessionStore.failAssistantMessage(userId, messageId, "AI服务暂时不可用，请稍后重试");
        }
        sendErrorEvent(session, requestId, sessionId, messageId, error.getMessage());
        cleanupRequestState(requestId, error);
    }

    private void submitCompletionMonitor(String requestId, String userId, WebSocketSession session) {
        try {
            chatMonitorExecutor.execute(() -> {
                try {
                    Thread.sleep(3000);
                    StringBuilder responseBuilder = responseBuilders.get(requestId);
                    if (responseBuilder == null) {
                        cleanupRequestState(requestId, new RuntimeException("响应构建器为空"));
                        return;
                    }

                    int lastLength = responseBuilder.length();
                    Thread.sleep(2000);
                    if (responseBuilder.length() == lastLength) {
                        finalizeResponse(requestId, userId, session, responseBuilder.toString());
                        return;
                    }

                    for (int i = 0; i < 5; i += 1) {
                        Thread.sleep(3000);
                        lastLength = responseBuilder.length();
                        Thread.sleep(1200);
                        if (responseBuilder.length() == lastLength) {
                            finalizeResponse(requestId, userId, session, responseBuilder.toString());
                            return;
                        }
                    }

                    finalizeResponse(requestId, userId, session, responseBuilder.toString());
                } catch (Exception exception) {
                    cleanupRequestState(requestId, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            handleStreamError(requestId, userId, session, new RuntimeException("系统繁忙，请稍后重试"));
        }
    }

    private void finalizeResponse(String requestId, String userId, WebSocketSession session, String completeResponse) {
        String messageId = requestAssistantMessageIds.get(requestId);
        String sessionId = requestSessionIds.get(requestId);
        if (messageId == null || sessionId == null) {
            cleanupRequestState(requestId, null);
            return;
        }

        Map<Integer, ReferenceInfo> referenceMapping = resolveReferenceMapping(messageId, sessionId, session.getId());
        ChatMessageRecord completed = chatSessionStore.completeAssistantMessage(userId, messageId, toSerializableReferenceMappings(referenceMapping));
        ChatSessionMeta latestSessionMeta = chatSessionStore.getSessionMeta(sessionId);
        sendCompletedEvent(session, requestId, latestSessionMeta, completed);
        cleanupRequestState(requestId, null);
    }

    private Map<Integer, ReferenceInfo> resolveReferenceMapping(String messageId, String sessionId, String wsSessionId) {
        Map<Integer, ReferenceInfo> byMessage = sessionReferenceMappings.get(messageId);
        if (byMessage != null) return byMessage;
        Map<Integer, ReferenceInfo> bySession = sessionReferenceMappings.get(sessionId);
        if (bySession != null) return bySession;
        Map<Integer, ReferenceInfo> byWs = sessionReferenceMappings.get(wsSessionId);
        if (byWs != null) return byWs;
        return null;
    }

    private void cleanupRequestState(String requestId, Throwable throwable) {
        responseBuilders.remove(requestId);
        stopFlags.remove(requestId);
        CompletableFuture<String> future = responseFutures.remove(requestId);
        if (throwable != null && future != null && !future.isDone()) {
            future.completeExceptionally(throwable);
        }
        if (throwable == null && future != null && !future.isDone()) {
            future.complete("");
        }

        String sessionId = requestSessionIds.remove(requestId);
        String wsSessionId = requestWsSessionIds.remove(requestId);
        String messageId = requestAssistantMessageIds.remove(requestId);

        if (sessionId != null) {
            activeRequestBySessionId.remove(sessionId, requestId);
        }
        if (wsSessionId != null) {
            activeRequestByWsSessionId.remove(wsSessionId, requestId);
        }
        if (messageId != null) {
            sessionReferenceMappings.remove(messageId);
        }
    }

    private String buildContext(List<SearchResult> searchResults,
                                String assistantMessageId,
                                String sessionId,
                                String wsSessionId,
                                String userMessage) {
        if (searchResults == null || searchResults.isEmpty()) {
            sessionReferenceMappings.remove(assistantMessageId);
            sessionReferenceMappings.remove(sessionId);
            sessionReferenceMappings.remove(wsSessionId);
            return "";
        }

        Map<Integer, ReferenceInfo> referenceMapping = new HashMap<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i += 1) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            if (snippet.length() > MAX_CONTEXT_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_CONTEXT_SNIPPET_LEN) + "…";
            }

            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            Integer pageNum = result.getPageNumber();
            if (pageNum != null && pageNum > 0) {
                context.append(String.format("[%d] (%s | 第%d页) %s\n", i + 1, fileLabel, pageNum, snippet));
            } else {
                context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
            }

            if (result.getFileMd5() != null) {
                referenceMapping.put(i + 1, buildReferenceInfo(result, fileLabel, userMessage));
            }
        }

        sessionReferenceMappings.put(assistantMessageId, referenceMapping);
        sessionReferenceMappings.put(sessionId, referenceMapping);
        sessionReferenceMappings.put(wsSessionId, referenceMapping);
        return context.toString();
    }

    private ReferenceInfo buildReferenceInfo(SearchResult result, String fileLabel, String userMessage) {
        String matchedChunkText = trimToMaxLength(
                result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(),
                MAX_MATCHED_CHUNK_LEN
        );
        String evidenceSnippet = buildEvidenceSnippet(userMessage, result.getAnchorText(), matchedChunkText);
        return new ReferenceInfo(
                result.getFileMd5(),
                fileLabel,
                result.getPageNumber(),
                result.getAnchorText(),
                result.getRetrievalMode(),
                buildRetrievalLabel(result.getRetrievalMode()),
                normalizeEvidenceText(userMessage),
                matchedChunkText,
                evidenceSnippet,
                result.getScore(),
                result.getChunkId()
        );
    }

    private ReferenceInfo fromPersistedReference(Map<String, Object> persisted) {
        if (persisted == null) {
            return null;
        }
        return new ReferenceInfo(
                String.valueOf(persisted.get("fileMd5")),
                String.valueOf(persisted.get("fileName")),
                persisted.get("pageNumber") == null ? null : Integer.parseInt(String.valueOf(persisted.get("pageNumber"))),
                persisted.get("anchorText") == null ? null : String.valueOf(persisted.get("anchorText")),
                persisted.get("retrievalMode") == null ? null : String.valueOf(persisted.get("retrievalMode")),
                persisted.get("retrievalLabel") == null ? null : String.valueOf(persisted.get("retrievalLabel")),
                persisted.get("retrievalQuery") == null ? null : String.valueOf(persisted.get("retrievalQuery")),
                persisted.get("matchedChunkText") == null ? null : String.valueOf(persisted.get("matchedChunkText")),
                persisted.get("evidenceSnippet") == null ? null : String.valueOf(persisted.get("evidenceSnippet")),
                persisted.get("score") == null ? null : Double.parseDouble(String.valueOf(persisted.get("score"))),
                persisted.get("chunkId") == null ? null : Integer.parseInt(String.valueOf(persisted.get("chunkId")))
        );
    }

    private String buildRetrievalLabel(String retrievalMode) {
        if ("TEXT_ONLY".equalsIgnoreCase(retrievalMode)) {
            return "关键词召回";
        }
        return "混合召回（语义相关 + 关键词命中）";
    }

    private String buildEvidenceSnippet(String userMessage, String anchorText, String matchedChunkText) {
        String normalizedAnchorText = normalizeEvidenceText(anchorText);
        if (!normalizedAnchorText.isBlank()) {
            return trimToMaxLength(normalizedAnchorText, MAX_EVIDENCE_SNIPPET_LEN);
        }

        String normalizedMatchedChunk = normalizeEvidenceText(matchedChunkText);
        if (normalizedMatchedChunk.isBlank()) {
            String normalizedUserMessage = normalizeEvidenceText(userMessage);
            if (!normalizedUserMessage.isBlank()) {
                return trimToMaxLength(normalizedUserMessage, MAX_EVIDENCE_SNIPPET_LEN);
            }
            return "";
        }

        String[] sentences = normalizedMatchedChunk.split("(?<=[。！？!?；;])");
        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.length() >= 12) {
                return trimToMaxLength(trimmedSentence, MAX_EVIDENCE_SNIPPET_LEN);
            }
        }
        return trimToMaxLength(normalizedMatchedChunk, MAX_EVIDENCE_SNIPPET_LEN);
    }

    private String trimToMaxLength(String value, int maxLength) {
        String normalized = normalizeEvidenceText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    private String normalizeEvidenceText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private ChatSessionMeta resolveTargetSession(String userId, String sessionId) {
        chatSessionStore.migrateLegacyConversationIfNeeded(userId);
        if (sessionId == null || sessionId.isBlank()) {
            return resolveDefaultSession(userId);
        }
        return chatSessionStore.requireOwnedSession(userId, sessionId);
    }

    private ChatSessionMeta resolveDefaultSession(String userId) {
        chatSessionStore.migrateLegacyConversationIfNeeded(userId);
        List<ChatSessionMeta> sessions = chatSessionStore.listSessions(userId);
        if (sessions.isEmpty()) {
            return chatSessionStore.createSession(userId);
        }
        return sessions.get(0);
    }

    private void ensureSessionNotBusy(String sessionId) {
        String activeRequestId = activeRequestBySessionId.get(sessionId);
        if (activeRequestId != null && responseBuilders.containsKey(activeRequestId)) {
            throw new CustomException("当前会话仍在生成中，请先停止后再试", HttpStatus.CONFLICT);
        }
    }

    private List<Map<String, String>> buildHistoryBeforeCurrentQuestion(String userId, String sessionId) {
        chatSessionStore.requireOwnedSession(userId, sessionId);
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessageRecord message : chatSessionStore.listMessages(sessionId, false)) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            item.put("timestamp", message.createdAt());
            history.add(item);
        }
        return history;
    }

    private List<Map<String, String>> buildHistoryBeforeAssistantPrompt(String userId, String sessionId, int assistantSeq) {
        chatSessionStore.requireOwnedSession(userId, sessionId);
        List<ChatMessageRecord> messages = chatSessionStore.listMessages(sessionId, false);
        ChatMessageRecord promptMessage = null;
        for (ChatMessageRecord message : messages) {
            if (message.seq() >= assistantSeq) {
                break;
            }
            if ("user".equals(message.role())) {
                promptMessage = message;
            }
        }
        if (promptMessage == null) {
            throw new CustomException("未找到用于重生成的用户提问", HttpStatus.BAD_REQUEST);
        }

        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessageRecord message : messages) {
            if (message.seq() >= promptMessage.seq()) {
                break;
            }
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            item.put("timestamp", message.createdAt());
            history.add(item);
        }
        return history;
    }

    private String findPromptForAssistant(String userId, String sessionId, int assistantSeq) {
        chatSessionStore.requireOwnedSession(userId, sessionId);
        String prompt = null;
        for (ChatMessageRecord message : chatSessionStore.listMessages(sessionId, false)) {
            if (message.seq() >= assistantSeq) {
                break;
            }
            if ("user".equals(message.role())) {
                prompt = message.content();
            }
        }
        if (prompt == null || prompt.isBlank()) {
            throw new CustomException("未找到用于重生成的用户提问", HttpStatus.BAD_REQUEST);
        }
        return prompt;
    }

    private String resolveRequestId(String requestId, String sessionId, String messageId, String wsSessionId) {
        if (requestId != null && responseBuilders.containsKey(requestId)) {
            return requestId;
        }
        if (sessionId != null && activeRequestBySessionId.containsKey(sessionId)) {
            return activeRequestBySessionId.get(sessionId);
        }
        if (wsSessionId != null && activeRequestByWsSessionId.containsKey(wsSessionId)) {
            return activeRequestByWsSessionId.get(wsSessionId);
        }
        if (messageId != null) {
            for (Map.Entry<String, String> entry : requestAssistantMessageIds.entrySet()) {
                if (Objects.equals(entry.getValue(), messageId)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private Map<Integer, Map<String, Object>> toSerializableReferenceMappings(Map<Integer, ReferenceInfo> referenceMapping) {
        if (referenceMapping == null || referenceMapping.isEmpty()) {
            return null;
        }
        Map<Integer, Map<String, Object>> serialized = new HashMap<>();
        for (Map.Entry<Integer, ReferenceInfo> entry : referenceMapping.entrySet()) {
            ReferenceInfo detail = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", detail.fileMd5());
            item.put("fileName", detail.fileName());
            item.put("pageNumber", detail.pageNumber());
            item.put("anchorText", detail.anchorText());
            item.put("retrievalMode", detail.retrievalMode());
            item.put("retrievalLabel", detail.retrievalLabel());
            item.put("retrievalQuery", detail.retrievalQuery());
            item.put("matchedChunkText", detail.matchedChunkText());
            item.put("evidenceSnippet", detail.evidenceSnippet());
            item.put("score", detail.score());
            item.put("chunkId", detail.chunkId());
            serialized.put(entry.getKey(), item);
        }
        return serialized;
    }

    private void sendAcceptedEvent(WebSocketSession session,
                                   String requestId,
                                   ChatSessionMeta sessionMeta,
                                   ChatMessageRecord userMessage,
                                   ChatMessageRecord assistantMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.accepted");
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionMeta.sessionId());
        payload.put("sessionMeta", sessionMeta);
        if (userMessage != null) {
            payload.put("userMessage", userMessage);
        }
        payload.put("assistantMessage", assistantMessage);
        sendJson(session, payload);
    }

    private void sendChunkEvent(WebSocketSession session, String requestId, String sessionId, String messageId, String delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.chunk");
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionId);
        payload.put("messageId", messageId);
        payload.put("delta", delta);
        payload.put("status", "loading");
        sendJson(session, payload);
    }

    private void sendCompletedEvent(WebSocketSession session,
                                    String requestId,
                                    ChatSessionMeta sessionMeta,
                                    ChatMessageRecord messageRecord) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.completed");
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionMeta.sessionId());
        payload.put("messageId", messageRecord.messageId());
        payload.put("status", "finished");
        payload.put("message", messageRecord);
        payload.put("sessionMeta", sessionMeta);
        sendJson(session, payload);
    }

    private void sendStoppedEvent(WebSocketSession session, String requestId, String sessionId, String messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.stopped");
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionId);
        payload.put("messageId", messageId);
        payload.put("message", "响应已停止");
        sendJson(session, payload);
    }

    private void sendErrorEvent(WebSocketSession session,
                                String requestId,
                                String sessionId,
                                String messageId,
                                String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.error");
        payload.put("requestId", requestId);
        payload.put("sessionId", sessionId);
        payload.put("messageId", messageId);
        payload.put("message", message == null || message.isBlank() ? "AI服务暂时不可用，请稍后重试" : message);
        sendJson(session, payload);
    }

    private void sendRateLimitMessage(WebSocketSession session, RateLimitExceededException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message.error");
        payload.put("code", 429);
        payload.put("message", exception.getMessage());
        payload.put("retryAfterSeconds", exception.getRetryAfterSeconds());
        sendJson(session, payload);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception exception) {
            logger.error("发送 WebSocket 消息失败: {}", exception.getMessage(), exception);
        }
    }

    public record ReferenceInfo(
            String fileMd5,
            String fileName,
            Integer pageNumber,
            String anchorText,
            String retrievalMode,
            String retrievalLabel,
            String retrievalQuery,
            String matchedChunkText,
            String evidenceSnippet,
            Double score,
            Integer chunkId
    ) {
    }
}
