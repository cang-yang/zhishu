package com.canggo.zhishu.controller;

import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.service.ChatHandler;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatMessageRecord;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatSessionMeta;
import com.canggo.zhishu.utils.JwtUtils;
import com.canggo.zhishu.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/users/chat", "/api/v1/users/conversation"})
public class ConversationController {

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;

    public ConversationController(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(@RequestHeader("Authorization") String token) {
        String userId = extractUserId(token);
        List<ChatSessionMeta> sessions = chatHandler.listSessions(userId);
        return ok(sessions);
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token) {
        String userId = extractUserId(token);
        ChatSessionMeta session = chatHandler.createSession(userId);
        return ok(session);
    }

    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<?> renameSession(@RequestHeader("Authorization") String token,
                                           @PathVariable String sessionId,
                                           @RequestBody Map<String, String> payload) {
        String userId = extractUserId(token);
        String title = payload.getOrDefault("title", "");
        ChatSessionMeta session = chatHandler.renameSession(userId, sessionId, title);
        return ok(session);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteSession(@RequestHeader("Authorization") String token,
                                           @PathVariable String sessionId) {
        String userId = extractUserId(token);
        chatHandler.deleteSession(userId, sessionId);
        return ok(Map.of("sessionId", sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> listMessages(@RequestHeader("Authorization") String token,
                                          @PathVariable String sessionId) {
        String userId = extractUserId(token);
        List<ChatMessageRecord> messages = chatHandler.listMessages(userId, sessionId);
        return ok(messages);
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(@RequestHeader("Authorization") String token,
                                           @PathVariable String messageId) {
        String userId = extractUserId(token);
        ChatMessageRecord message = chatHandler.deleteMessage(userId, messageId);
        ChatSessionMeta sessionMeta = chatHandler.getSessionMeta(userId, message.sessionId());
        return ok(Map.of(
                "message", message,
                "sessionMeta", sessionMeta
        ));
    }

    @PostMapping("/messages/{messageId}/regenerate")
    public ResponseEntity<?> preflightRegenerate(@RequestHeader("Authorization") String token,
                                                 @PathVariable String messageId) {
        String userId = extractUserId(token);
        ChatMessageRecord message = chatHandler.ensureRegeneratable(userId, messageId);
        return ok(Map.of(
                "messageId", message.messageId(),
                "sessionId", message.sessionId(),
                "allowed", true
        ));
    }

    @GetMapping
    public ResponseEntity<?> getConversations(@RequestHeader("Authorization") String token,
                                              @RequestParam(required = false) String sessionId) {
        String userId = extractUserId(token);
        List<ChatSessionMeta> sessions = chatHandler.listSessions(userId);
        if (sessions.isEmpty()) {
            return ok(List.of());
        }

        String targetSessionId = sessionId;
        if (targetSessionId == null || targetSessionId.isBlank()) {
            targetSessionId = sessions.get(0).sessionId();
        }
        List<ChatMessageRecord> messages = chatHandler.listMessages(userId, targetSessionId);
        return ok(messages);
    }

    private String extractUserId(String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        String jwtToken = token.replace("Bearer ", "");
        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        if (userId == null || userId.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }

    private ResponseEntity<?> ok(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
