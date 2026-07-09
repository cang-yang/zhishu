package com.canggo.zhishu.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.canggo.zhishu.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedisChatSessionStore {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SESSION_TITLE_MAX_LENGTH = 24;
    private static final int SESSION_PREVIEW_MAX_LENGTH = 72;

    @Value("${zhishu.experiment.feature:none}") private String experimentFeature;
    @Value("${zhishu.experiment.arm:after}") private String experimentArm;
    @Value("${zhishu.redis.buffer.ttl-seconds:3600}") private long bufferTtlSeconds;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatSessionStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ChatSessionMeta createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        ChatSessionMeta meta = new ChatSessionMeta(
                sessionId,
                userId,
                "新会话",
                currentTimestamp(),
                currentTimestamp(),
                null,
                0,
                "",
                false,
                null,
                null,
                null
        );
        saveSessionMeta(meta);
        return meta;
    }

    public List<ChatSessionMeta> listSessions(String userId) {
        Set<String> sessionIds = redisTemplate.opsForSet().members(userSessionsKey(userId));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new ArrayList<>();
        }

        return sessionIds.stream()
                .map(this::getSessionMeta)
                .filter(Objects::nonNull)
                .filter(meta -> !meta.deleted())
                .sorted(Comparator.comparing(ChatSessionMeta::updatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .collect(Collectors.toList());
    }

    public ChatSessionMeta getSessionMeta(String sessionId) {
        String json = redisTemplate.opsForValue().get(sessionMetaKey(sessionId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ChatSessionMeta.class);
        } catch (JsonProcessingException e) {
            throw new CustomException("解析会话信息失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public List<ChatMessageRecord> listMessages(String sessionId, boolean includeDeleted) {
        List<String> messageIds = readMessageIds(sessionId);
        List<ChatMessageRecord> messages = new ArrayList<>();
        for (String messageId : messageIds) {
            ChatMessageRecord message = getMessage(messageId);
            if (message == null) continue;
            if (!includeDeleted && message.deleted()) continue;
            messages.add(message);
        }
        messages.sort(Comparator.comparingInt(ChatMessageRecord::seq));
        return messages;
    }

    public ChatMessageRecord getMessage(String messageId) {
        String json = redisTemplate.opsForValue().get(messageKey(messageId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ChatMessageRecord message = objectMapper.readValue(json, ChatMessageRecord.class);
            if (("loading".equals(message.status()) || "pending".equals(message.status())) && !isBaselineArm()) {
                String buffered = redisTemplate.opsForValue().get(messageBufferKey(messageId));
                if (buffered != null) {
                    return message.withContent(buffered);
                }
            }
            return message;
        } catch (JsonProcessingException e) {
            throw new CustomException("解析消息失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public ChatMessageRecord createUserMessage(String userId, String sessionId, String content) {
        ChatSessionMeta meta = requireOwnedSession(userId, sessionId);
        int seq = nextSequence(sessionId);
        String createdAt = currentTimestamp();
        ChatMessageRecord message = new ChatMessageRecord(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                "user",
                content,
                seq,
                "finished",
                createdAt,
                createdAt,
                1,
                0,
                null,
                false,
                null,
                true,
                false
        );
        saveMessage(message);
        appendMessageToSession(userId, meta, message);
        return message;
    }

    public ChatMessageRecord createAssistantPlaceholder(String userId, String sessionId) {
        ChatSessionMeta meta = requireOwnedSession(userId, sessionId);
        int seq = nextSequence(sessionId);
        String createdAt = currentTimestamp();
        ChatMessageRecord message = new ChatMessageRecord(
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                "assistant",
                "",
                seq,
                "pending",
                createdAt,
                createdAt,
                1,
                0,
                null,
                false,
                null,
                true,
                isLastVisibleAssistantMessage(sessionId, null)
        );
        saveMessage(message);
        appendMessageToSession(userId, meta, message);
        return message;
    }

    public ChatMessageRecord appendAssistantChunk(String userId, String messageId, String chunk) {
        ChatMessageRecord current = requireOwnedMessage(userId, messageId);
        if (isBaselineArm()) {
            ChatMessageRecord updated = current.withContent((current.content() == null ? "" : current.content()) + chunk)
                    .withStatus("loading")
                    .withUpdatedAt(currentTimestamp());
            saveMessage(updated);
            refreshSessionAfterMessageMutation(userId, updated.sessionId());
            return updated;
        }
        String bufferKey = messageBufferKey(messageId);
        if (appendWithRetry(bufferKey, chunk)) {
            return current.withContent((current.content() == null ? "" : current.content()) + chunk)
                    .withStatus("loading")
                    .withUpdatedAt(currentTimestamp());
        }
        return appendBaselinePath(current, userId, chunk, bufferKey);
    }

    private boolean appendWithRetry(String bufferKey, String chunk) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                redisTemplate.opsForValue().append(bufferKey, chunk);
                try {
                    redisTemplate.expire(bufferKey, Duration.ofSeconds(bufferTtlSeconds));
                } catch (Exception expireEx) {
                    // TTL 未刷新, 下次 APPEND 后重试 EXPIRE
                }
                return true;
            } catch (Exception appendEx) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private ChatMessageRecord appendBaselinePath(ChatMessageRecord current, String userId, String chunk, String bufferKey) {
        String buffered = redisTemplate.opsForValue().get(bufferKey);
        String accumulated = (buffered == null ? "" : buffered) + chunk;
        ChatMessageRecord updated = current.withContent(accumulated)
                .withStatus("loading")
                .withUpdatedAt(currentTimestamp());
        saveMessage(updated);
        try {
            redisTemplate.opsForValue().set(bufferKey, accumulated);
            redisTemplate.expire(bufferKey, Duration.ofSeconds(bufferTtlSeconds));
        } catch (Exception setEx) {
            // SET 失败 (Redis 持续故障, 罕见窗口): refreshSession 可能覆盖 message.content, 当前 chunk 丢失; 接受此窗口, failAssistantMessage 兜底
        }
        refreshSessionAfterMessageMutation(userId, updated.sessionId());
        return updated;
    }

    public ChatMessageRecord completeAssistantMessage(String userId, String messageId, Map<Integer, Map<String, Object>> referenceMappings) {
        ChatMessageRecord current = requireOwnedMessage(userId, messageId);
        // getMessage (经 requireOwnedMessage) 已在 loading||pending 态合并 buffer 到 current.content();
        // 无需显式 GET buffer (消除冗余 GET, complete 命令数 8+2N)
        String finalContent = current.content() == null ? "" : current.content();
        ChatMessageRecord updated = current.withContent(finalContent)
                .withStatus("finished")
                .withReferenceMappings(referenceMappings)
                .withUpdatedAt(currentTimestamp());
        saveMessage(updated);
        refreshSessionAfterMessageMutation(userId, updated.sessionId());
        if (!isBaselineArm()) {
            redisTemplate.delete(messageBufferKey(messageId));
        }
        return updated;
    }

    public ChatMessageRecord failAssistantMessage(String userId, String messageId, String errorMessage) {
        ChatMessageRecord current = requireOwnedMessage(userId, messageId);
        if (!isBaselineArm()) {
            redisTemplate.delete(messageBufferKey(messageId));
        }
        ChatMessageRecord updated = current.withStatus("error")
                .withContent(errorMessage)
                .withUpdatedAt(currentTimestamp());
        saveMessage(updated);
        refreshSessionAfterMessageMutation(userId, updated.sessionId());
        return updated;
    }

    public ChatMessageRecord deleteMessage(String userId, String messageId) {
        ChatMessageRecord current = requireOwnedMessage(userId, messageId);
        if (current.deleted()) {
            return current;
        }
        ChatMessageRecord updated = current.withDeleted(true)
                .withUpdatedAt(currentTimestamp());
        saveMessage(updated);
        refreshSessionAfterMessageMutation(userId, updated.sessionId());
        return updated;
    }

    public ChatMessageRecord regenerateAssistantMessage(String userId, String messageId) {
        ChatMessageRecord current = requireOwnedMessage(userId, messageId);
        if (!"assistant".equals(current.role())) {
            throw new CustomException("仅支持重生成 AI 回复", HttpStatus.BAD_REQUEST);
        }
        if (!isLastVisibleAssistantMessage(current.sessionId(), current.messageId())) {
            throw new CustomException("仅允许重生成最后一条 AI 回复", HttpStatus.BAD_REQUEST);
        }
        ChatMessageRecord updated = current.withContent("")
                .withStatus("pending")
                .withVersion(current.version() + 1)
                .withRegenerateCount(current.regenerateCount() + 1)
                .withReferenceMappings(null)
                .withUpdatedAt(currentTimestamp())
                .withRegeneratable(true);
        saveMessage(updated);
        refreshSessionAfterMessageMutation(userId, updated.sessionId());
        return updated;
    }

    public void deleteSession(String userId, String sessionId) {
        ChatSessionMeta current = requireOwnedSession(userId, sessionId);
        ChatSessionMeta updated = current.withDeleted(true).withUpdatedAt(currentTimestamp());
        saveSessionMeta(updated);
        List<ChatMessageRecord> messages = listMessages(sessionId, true);
        for (ChatMessageRecord message : messages) {
            saveMessage(message.withDeleted(true).withUpdatedAt(currentTimestamp()));
        }
    }

    public ChatSessionMeta renameSession(String userId, String sessionId, String title) {
        ChatSessionMeta current = requireOwnedSession(userId, sessionId);
        ChatSessionMeta updated = current.withTitle(normalizeSessionTitle(title))
                .withUpdatedAt(currentTimestamp());
        saveSessionMeta(updated);
        return updated;
    }

    public ChatSessionMeta requireOwnedSession(String userId, String sessionId) {
        ChatSessionMeta meta = getSessionMeta(sessionId);
        if (meta == null || meta.deleted()) {
            throw new CustomException("会话不存在", HttpStatus.NOT_FOUND);
        }
        if (!Objects.equals(meta.userId(), userId)) {
            throw new CustomException("无权访问该会话", HttpStatus.FORBIDDEN);
        }
        return meta;
    }

    public ChatMessageRecord requireOwnedMessage(String userId, String messageId) {
        ChatMessageRecord message = getMessage(messageId);
        if (message == null) {
            throw new CustomException("消息不存在", HttpStatus.NOT_FOUND);
        }
        if (!Objects.equals(message.userId(), userId)) {
            throw new CustomException("无权访问该消息", HttpStatus.FORBIDDEN);
        }
        return message;
    }

    public List<Map<String, String>> buildHistoryForModel(String userId, String sessionId, String assistantMessageIdToExclude) {
        requireOwnedSession(userId, sessionId);
        return listMessages(sessionId, false).stream()
                .filter(message -> !Objects.equals(message.messageId(), assistantMessageIdToExclude))
                .filter(message -> message.content() != null && !message.content().isBlank())
                .sorted(Comparator.comparingInt(ChatMessageRecord::seq))
                .map(message -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", message.content());
                    item.put("timestamp", message.createdAt());
                    return item;
                })
                .collect(Collectors.toList());
    }

    public void migrateLegacyConversationIfNeeded(String userId) {
        String fallbackConversationId = redisTemplate.opsForValue().get("user:" + userId + ":current_conversation");
        if (fallbackConversationId == null || fallbackConversationId.isBlank()) {
            return;
        }
        if (!listSessions(userId).isEmpty()) {
            return;
        }

        String legacyJson = redisTemplate.opsForValue().get("conversation:" + fallbackConversationId);
        if (legacyJson == null || legacyJson.isBlank()) {
            return;
        }

        try {
            List<Map<String, Object>> history = objectMapper.readValue(legacyJson, new TypeReference<List<Map<String, Object>>>() {});
            ChatSessionMeta meta = new ChatSessionMeta(
                    fallbackConversationId,
                    userId,
                    "历史会话",
                    currentTimestamp(),
                    currentTimestamp(),
                    null,
                    0,
                    "",
                    false,
                    null,
                    null,
                    null
            );
            saveSessionMeta(meta);
            redisTemplate.opsForSet().add(userSessionsKey(userId), fallbackConversationId);

            int seq = 0;
            for (Map<String, Object> item : history) {
                seq += 1;
                String role = String.valueOf(item.getOrDefault("role", "assistant"));
                String content = String.valueOf(item.getOrDefault("content", ""));
                String timestamp = String.valueOf(item.getOrDefault("timestamp", currentTimestamp()));
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> referenceMappings = (Map<String, Map<String, Object>>) item.get("referenceMappings");

                ChatMessageRecord message = new ChatMessageRecord(
                        UUID.randomUUID().toString(),
                        fallbackConversationId,
                        userId,
                        role,
                        content,
                        seq,
                        "finished",
                        timestamp,
                        timestamp,
                        1,
                        0,
                        referenceMappings == null ? null : convertReferenceMappings(referenceMappings),
                        false,
                        null,
                        true,
                        false
                );
                saveMessage(message);
                appendMessageId(fallbackConversationId, message.messageId());
            }

            refreshSessionAfterMessageMutation(userId, fallbackConversationId);
        } catch (JsonProcessingException e) {
            throw new CustomException("迁移旧会话失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void appendMessageToSession(String userId, ChatSessionMeta currentMeta, ChatMessageRecord message) {
        appendMessageId(message.sessionId(), message.messageId());
        refreshSessionAfterMessageMutation(userId, currentMeta.sessionId());
    }

    private void refreshSessionAfterMessageMutation(String userId, String sessionId) {
        ChatSessionMeta currentMeta = requireOwnedSession(userId, sessionId);
        List<ChatMessageRecord> visibleMessages = listMessages(sessionId, false);
        ChatMessageRecord firstUserMessage = visibleMessages.stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst()
                .orElse(null);
        ChatMessageRecord lastVisibleMessage = visibleMessages.isEmpty() ? null : visibleMessages.get(visibleMessages.size() - 1);
        ChatMessageRecord lastAssistant = null;
        for (int i = visibleMessages.size() - 1; i >= 0; i -= 1) {
            ChatMessageRecord candidate = visibleMessages.get(i);
            if ("assistant".equals(candidate.role())) {
                lastAssistant = candidate;
                break;
            }
        }

        String title = currentMeta.title();
        if ((title == null || title.isBlank() || "新会话".equals(title) || "历史会话".equals(title)) && firstUserMessage != null) {
            title = normalizeSessionTitle(firstUserMessage.content());
        }

        String preview = lastVisibleMessage == null ? "" : normalizePreview(lastVisibleMessage.content());
        ChatSessionMeta updatedMeta = currentMeta
                .withTitle(title)
                .withUpdatedAt(currentTimestamp())
                .withLastMessageAt(lastVisibleMessage == null ? null : lastVisibleMessage.updatedAt())
                .withMessageCount(visibleMessages.size())
                .withLatestPreview(preview)
                .withFirstMessageId(firstUserMessage == null ? null : firstUserMessage.messageId())
                .withLastMessageId(lastVisibleMessage == null ? null : lastVisibleMessage.messageId())
                .withLastAssistantMessageId(lastAssistant == null ? null : lastAssistant.messageId());
        saveSessionMeta(updatedMeta);

        for (ChatMessageRecord message : visibleMessages) {
            boolean regeneratable = "assistant".equals(message.role()) && Objects.equals(updatedMeta.lastAssistantMessageId(), message.messageId())
                    && Objects.equals(updatedMeta.lastMessageId(), message.messageId());
            saveMessage(message.withRegeneratable(regeneratable).withDeletable(true));
        }
    }

    private boolean isLastVisibleAssistantMessage(String sessionId, String messageId) {
        List<ChatMessageRecord> visibleMessages = listMessages(sessionId, false);
        ChatMessageRecord lastAssistant = null;
        ChatMessageRecord lastVisible = visibleMessages.isEmpty() ? null : visibleMessages.get(visibleMessages.size() - 1);
        for (int i = visibleMessages.size() - 1; i >= 0; i -= 1) {
            ChatMessageRecord candidate = visibleMessages.get(i);
            if ("assistant".equals(candidate.role())) {
                lastAssistant = candidate;
                break;
            }
        }
        if (lastAssistant == null || lastVisible == null) {
            return false;
        }
        if (messageId == null) {
            return Objects.equals(lastVisible.messageId(), lastAssistant.messageId());
        }
        return Objects.equals(lastAssistant.messageId(), messageId) && Objects.equals(lastVisible.messageId(), messageId);
    }

    private void appendMessageId(String sessionId, String messageId) {
        List<String> ids = readMessageIds(sessionId);
        ids.add(messageId);
        writeMessageIds(sessionId, ids);
    }

    private List<String> readMessageIds(String sessionId) {
        String json = redisTemplate.opsForValue().get(sessionMessagesKey(sessionId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException("解析会话消息索引失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void writeMessageIds(String sessionId, List<String> ids) {
        try {
            redisTemplate.opsForValue().set(sessionMessagesKey(sessionId), objectMapper.writeValueAsString(ids));
        } catch (JsonProcessingException e) {
            throw new CustomException("写入会话消息索引失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private int nextSequence(String sessionId) {
        Long value = redisTemplate.opsForValue().increment(sessionSequenceKey(sessionId));
        if (value == null) {
            throw new CustomException("生成消息序号失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return value.intValue();
    }

    private void saveSessionMeta(ChatSessionMeta meta) {
        try {
            redisTemplate.opsForValue().set(sessionMetaKey(meta.sessionId()), objectMapper.writeValueAsString(meta));
            redisTemplate.opsForSet().add(userSessionsKey(meta.userId()), meta.sessionId());
        } catch (JsonProcessingException e) {
            throw new CustomException("写入会话信息失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void saveMessage(ChatMessageRecord message) {
        try {
            redisTemplate.opsForValue().set(messageKey(message.messageId()), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new CustomException("写入消息失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String normalizeSessionTitle(String raw) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            return "新会话";
        }
        if (value.length() <= SESSION_TITLE_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, SESSION_TITLE_MAX_LENGTH) + "...";
    }

    private String normalizePreview(String raw) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (value.length() <= SESSION_PREVIEW_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, SESSION_PREVIEW_MAX_LENGTH) + "...";
    }

    private String currentTimestamp() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private Map<Integer, Map<String, Object>> convertReferenceMappings(Map<String, Map<String, Object>> source) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            try {
                result.put(Integer.parseInt(entry.getKey()), new HashMap<>(entry.getValue()));
            } catch (NumberFormatException ignore) {
                // ignore invalid legacy key
            }
        }
        return result;
    }

    private String userSessionsKey(String userId) {
        return "chat:user:" + userId + ":sessions";
    }

    private String sessionMetaKey(String sessionId) {
        return "chat:session:" + sessionId + ":meta";
    }

    private String sessionMessagesKey(String sessionId) {
        return "chat:session:" + sessionId + ":messages";
    }

    private String sessionSequenceKey(String sessionId) {
        return "chat:session:" + sessionId + ":seq";
    }

    private String messageKey(String messageId) {
        return "chat:message:" + messageId;
    }

    private String messageBufferKey(String messageId) {
        return "chat:message:" + messageId + ":buffer";
    }

    private boolean isBaselineArm() {
        return "ZH-F05".equals(experimentFeature) && "baseline".equals(experimentArm);
    }

    public record ChatSessionMeta(
            String sessionId,
            String userId,
            String title,
            String createdAt,
            String updatedAt,
            String lastMessageAt,
            int messageCount,
            String latestPreview,
            boolean deleted,
            String firstMessageId,
            String lastMessageId,
            String lastAssistantMessageId
    ) {
        public ChatSessionMeta withTitle(String value) {
            return new ChatSessionMeta(sessionId, userId, value, createdAt, updatedAt, lastMessageAt, messageCount, latestPreview, deleted, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withUpdatedAt(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, value, lastMessageAt, messageCount, latestPreview, deleted, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withLastMessageAt(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, value, messageCount, latestPreview, deleted, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withMessageCount(int value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, value, latestPreview, deleted, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withLatestPreview(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, messageCount, value, deleted, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withDeleted(boolean value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, messageCount, latestPreview, value, firstMessageId, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withFirstMessageId(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, messageCount, latestPreview, deleted, value, lastMessageId, lastAssistantMessageId);
        }

        public ChatSessionMeta withLastMessageId(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, messageCount, latestPreview, deleted, firstMessageId, value, lastAssistantMessageId);
        }

        public ChatSessionMeta withLastAssistantMessageId(String value) {
            return new ChatSessionMeta(sessionId, userId, title, createdAt, updatedAt, lastMessageAt, messageCount, latestPreview, deleted, firstMessageId, lastMessageId, value);
        }
    }

    public record ChatMessageRecord(
            String messageId,
            String sessionId,
            String userId,
            String role,
            String content,
            int seq,
            String status,
            String createdAt,
            String updatedAt,
            int version,
            int regenerateCount,
            Map<Integer, Map<String, Object>> referenceMappings,
            boolean deleted,
            String deletedAt,
            boolean deletable,
            boolean regeneratable
    ) {
        public ChatMessageRecord withContent(String value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, value, seq, status, createdAt, updatedAt, version, regenerateCount, referenceMappings, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withStatus(String value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, value, createdAt, updatedAt, version, regenerateCount, referenceMappings, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withUpdatedAt(String value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, value, version, regenerateCount, referenceMappings, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withVersion(int value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, value, regenerateCount, referenceMappings, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withRegenerateCount(int value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, version, value, referenceMappings, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withReferenceMappings(Map<Integer, Map<String, Object>> value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, version, regenerateCount, value, deleted, deletedAt, deletable, regeneratable);
        }

        public ChatMessageRecord withDeleted(boolean value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, version, regenerateCount, referenceMappings, value, value ? LocalDateTime.now().format(DATE_TIME_FORMATTER) : null, deletable, regeneratable);
        }

        public ChatMessageRecord withDeletable(boolean value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, version, regenerateCount, referenceMappings, deleted, deletedAt, value, regeneratable);
        }

        public ChatMessageRecord withRegeneratable(boolean value) {
            return new ChatMessageRecord(messageId, sessionId, userId, role, content, seq, status, createdAt, updatedAt, version, regenerateCount, referenceMappings, deleted, deletedAt, deletable, value);
        }
    }
}
