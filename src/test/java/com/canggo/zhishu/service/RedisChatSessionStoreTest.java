package com.canggo.zhishu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.canggo.zhishu.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ZH-F05 RedisChatSessionStore buffer 改造单元测试。
 * 聚焦 after 路径新逻辑: appendAssistantChunk APPEND buffer (不 saveMessage), getMessage 合并 buffer,
 * completeAssistantMessage/failAssistantMessage DEL buffer, isBaselineArm 门控, ownership 越权防护。
 * refreshSessionAfterMessageMutation 的完整链路在 G4 真实 Redis 采集时验证 (canonical diff)。
 */
@ExtendWith(MockitoExtension.class)
class RedisChatSessionStoreTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;

    RedisChatSessionStore store;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_ID = "userA";
    private static final String SESSION_ID = "sess-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String MESSAGE_KEY = "chat:message:" + MESSAGE_ID;
    private static final String BUFFER_KEY = "chat:message:" + MESSAGE_ID + ":buffer";
    private static final String META_KEY = "chat:session:" + SESSION_ID + ":meta";
    private static final String MESSAGES_KEY = "chat:session:" + SESSION_ID + ":messages";
    private static final String SESSIONS_KEY = "chat:user:" + USER_ID + ":sessions";

    @BeforeEach
    void setup() throws Exception {
        store = new RedisChatSessionStore(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        setField(store, "experimentFeature", "ZH-F05");
        setField(store, "experimentArm", "after");
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private String messageJson(String content, String status) {
        RedisChatSessionStore.ChatMessageRecord rec = new RedisChatSessionStore.ChatMessageRecord(
                MESSAGE_ID, SESSION_ID, USER_ID, "assistant", content, 1, status,
                "2026-07-08T10:00:00", "2026-07-08T10:00:00", 1, 0, null, false, null, true, false);
        try {
            return objectMapper.writeValueAsString(rec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String messageJsonOtherUser() {
        RedisChatSessionStore.ChatMessageRecord rec = new RedisChatSessionStore.ChatMessageRecord(
                MESSAGE_ID, SESSION_ID, "otherUser", "assistant", "", 1, "pending",
                "2026-07-08T10:00:00", "2026-07-08T10:00:00", 1, 0, null, false, null, true, false);
        try {
            return objectMapper.writeValueAsString(rec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String metaJson() {
        RedisChatSessionStore.ChatSessionMeta meta = new RedisChatSessionStore.ChatSessionMeta(
                SESSION_ID, USER_ID, "新会话", "2026-07-08T10:00:00", "2026-07-08T10:00:00",
                null, 0, "", false, null, null, null);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubRefreshSessionEmptyMessages() throws Exception {
        // refreshSessionAfterMessageMutation with empty visible messages: requireOwnedSession + listMessages(empty) + saveSessionMeta + for(empty)
        lenient().when(valueOps.get(META_KEY)).thenReturn(metaJson());
        lenient().when(valueOps.get(MESSAGES_KEY)).thenReturn("[]");
    }

    @Test
    void appendAssistantChunk_after_appendsToBuffer_doesNotSaveMessage() {
        // after 路径: requireOwnedMessage→getMessage (pending→合并 buffer=null) + appendWithRetry (APPEND + EXPIRE)
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn(null); // getMessage 合并时 buffer 不存在

        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "chunk1");

        // after 路径: APPEND buffer 调用 1 次
        verify(valueOps).append(BUFFER_KEY, "chunk1");
        // EXPIRE 调用 (TTL 刷新)
        verify(redisTemplate).expire(eq(BUFFER_KEY), any(Duration.class));
        // after 路径不 saveMessage (message content 不变): valueOps.set(MESSAGE_KEY, ...) 从不调用
        verify(valueOps, never()).set(eq(MESSAGE_KEY), anyString());
    }

    @Test
    void appendAssistantChunk_baseline_savesMessageAndRefreshes() throws Exception {
        setField(store, "experimentArm", "baseline");
        // baseline 路径: getMessage 不合并 buffer (isBaselineArm=true); saveMessage + refreshSession
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        stubRefreshSessionEmptyMessages();

        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "chunk1");

        // baseline 路径: saveMessage (valueOps.set(MESSAGE_KEY, ...)) 至少调用 1 次
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());
        // baseline 路径不 APPEND buffer
        verify(valueOps, never()).append(eq(BUFFER_KEY), anyString());
    }

    @Test
    void getMessage_pending_after_mergesBuffer() {
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn("accumulated-buffer");

        RedisChatSessionStore.ChatMessageRecord result = store.getMessage(MESSAGE_ID);

        assertNotNull(result);
        assertEquals("accumulated-buffer", result.content(), "after 路径 pending 态应合并 buffer content");
    }

    @Test
    void getMessage_loading_after_mergesBuffer() {
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "loading"));
        when(valueOps.get(BUFFER_KEY)).thenReturn("accumulated-buffer");

        RedisChatSessionStore.ChatMessageRecord result = store.getMessage(MESSAGE_ID);

        assertNotNull(result);
        assertEquals("accumulated-buffer", result.content(), "loading 态应合并 buffer");
    }

    @Test
    void getMessage_finished_doesNotMergeBuffer() {
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("final-content", "finished"));

        RedisChatSessionStore.ChatMessageRecord result = store.getMessage(MESSAGE_ID);

        assertNotNull(result);
        assertEquals("final-content", result.content(), "finished 态直接返回 message content");
        verify(valueOps, never()).get(BUFFER_KEY);
    }

    @Test
    void getMessage_pending_baseline_doesNotMergeBuffer() throws Exception {
        setField(store, "experimentArm", "baseline");
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("baseline-content", "loading"));

        RedisChatSessionStore.ChatMessageRecord result = store.getMessage(MESSAGE_ID);

        assertNotNull(result);
        assertEquals("baseline-content", result.content(), "baseline arm 不合并 buffer");
        verify(valueOps, never()).get(BUFFER_KEY);
    }

    @Test
    void completeAssistantMessage_after_readsBufferAndDeletes() throws Exception {
        // after complete: requireOwnedMessage→getMessage (pending→合并 buffer 到 current.content) + saveMessage(finalContent=current.content) + refresh + DEL buffer
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn("final-accumulated");
        stubRefreshSessionEmptyMessages();

        store.completeAssistantMessage(USER_ID, MESSAGE_ID, Collections.emptyMap());

        // getMessage 合并 buffer (via requireOwnedMessage), finalContent = current.content = buffer 累积
        verify(valueOps, atLeastOnce()).get(BUFFER_KEY);
        // saveMessage (写最终整消息 content=final-accumulated, status=finished)
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());
        // DEL buffer
        verify(redisTemplate).delete(BUFFER_KEY);
    }

    @Test
    void completeAssistantMessage_baseline_usesCurrentContent() throws Exception {
        setField(store, "experimentArm", "baseline");
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("baseline-accumulated", "loading"));
        stubRefreshSessionEmptyMessages();

        store.completeAssistantMessage(USER_ID, MESSAGE_ID, Collections.emptyMap());

        // baseline 不读 buffer (用 current.content)
        verify(valueOps, never()).get(BUFFER_KEY);
        // baseline 不 DEL buffer (无 buffer)
        verify(redisTemplate, never()).delete(BUFFER_KEY);
        // saveMessage 写最终消息
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());
    }

    @Test
    void failAssistantMessage_after_deletesBuffer() throws Exception {
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        stubRefreshSessionEmptyMessages();

        store.failAssistantMessage(USER_ID, MESSAGE_ID, "stream error");

        // after fail: DEL buffer 清理
        verify(redisTemplate).delete(BUFFER_KEY);
        // saveMessage (content=errorMessage, status=error)
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());
    }

    @Test
    void failAssistantMessage_baseline_doesNotDeleteBuffer() throws Exception {
        setField(store, "experimentArm", "baseline");
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "loading"));
        stubRefreshSessionEmptyMessages();

        store.failAssistantMessage(USER_ID, MESSAGE_ID, "stream error");

        // baseline 无 buffer, 不 DEL
        verify(redisTemplate, never()).delete(BUFFER_KEY);
    }

    @Test
    void appendAssistantChunk_forbiddenWhenNotOwner() {
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJsonOtherUser());

        CustomException ex = assertThrows(CustomException.class,
                () -> store.appendAssistantChunk(USER_ID, MESSAGE_ID, "chunk1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(valueOps, never()).append(anyString(), anyString());
    }

    @Test
    void isBaselineArm_gateLogic() throws Exception {
        setField(store, "experimentFeature", "ZH-F05");
        setField(store, "experimentArm", "baseline");
        // baseline arm: isBaselineArm=true → appendAssistantChunk 走 baseline 路径
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        stubRefreshSessionEmptyMessages();
        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "c");
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());

        // 切换 after
        org.mockito.Mockito.reset(valueOps, redisTemplate, setOps);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        setField(store, "experimentArm", "after");
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn(null);
        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "c");
        verify(valueOps).append(BUFFER_KEY, "c");
        verify(valueOps, never()).set(eq(MESSAGE_KEY), anyString());
    }

    @Test
    void appendAssistantChunk_appendAlwaysFails_fallsBackToBaselinePath() throws Exception {
        // F-MAJOR-1 + F-MAJOR-2: appendWithRetry 3 次全败 → appendBaselinePath 降级 (chunk 不丢 + SET buffer 同步)
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn("old-buffer"); // getMessage 合并 + appendBaselinePath 读
        stubRefreshSessionEmptyMessages();
        when(valueOps.append(eq(BUFFER_KEY), anyString())).thenThrow(new RuntimeException("redis down"));

        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "chunkX");

        // append 重试 3 次 (F-MAJOR-1: appendWithRetry 3 次退避)
        verify(valueOps, times(3)).append(eq(BUFFER_KEY), anyString());
        // 降级 appendBaselinePath: saveMessage 写 message content = old-buffer + chunkX (chunk 不丢)
        verify(valueOps, atLeastOnce()).set(eq(MESSAGE_KEY), anyString());
        // SET buffer 同步 (major-3.2 修复): set(BUFFER_KEY, "old-bufferchunkX") 避免 refreshSession getMessage 合并 old_buffer 覆盖
        verify(valueOps).set(eq(BUFFER_KEY), eq("old-bufferchunkX"));
        // refreshSession 调用 (降级路径刷 session; appendBaselinePath 的 EXPIRE)
        verify(redisTemplate, atLeastOnce()).expire(eq(BUFFER_KEY), any(Duration.class));
    }

    @Test
    void appendAssistantChunk_appendSucceedsOnSecondAttempt_noFallback() {
        // F-MAJOR-1: append 第 1 次失败, 第 2 次成功 → appendWithRetry 成功, 不降级 baselinePath
        when(valueOps.get(MESSAGE_KEY)).thenReturn(messageJson("", "pending"));
        when(valueOps.get(BUFFER_KEY)).thenReturn(null); // getMessage 合并时 buffer 不存在
        when(valueOps.append(eq(BUFFER_KEY), anyString()))
                .thenThrow(new RuntimeException("redis transient"))
                .thenReturn(null); // append 返回 void, 第 2 次成功

        store.appendAssistantChunk(USER_ID, MESSAGE_ID, "chunkY");

        // append 调 2 次 (第 1 次失败 + 第 2 次成功)
        verify(valueOps, times(2)).append(eq(BUFFER_KEY), anyString());
        // 成功后 expire 刷新 TTL
        verify(redisTemplate).expire(eq(BUFFER_KEY), any(Duration.class));
        // 不降级: saveMessage never (after 路径不 saveMessage, 写放大消除)
        verify(valueOps, never()).set(eq(MESSAGE_KEY), anyString());
        // 不降级: 不 SET buffer 同步 (appendBaselinePath 才 set buffer)
        verify(valueOps, never()).set(eq(BUFFER_KEY), anyString());
    }
}
