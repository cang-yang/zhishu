package com.canggo.zhishu.controller;

import com.canggo.zhishu.handler.ChatWebSocketHandler;
import com.canggo.zhishu.service.ChatHandler;
import com.canggo.zhishu.utils.JwtUtils;
import com.canggo.zhishu.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Component
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;

    public ChatController(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid token", "data", null));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid token", "data", null));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "Token生成失败",
                    "data", null
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取WebSocket停止指令Token成功",
                "data", Map.of("cmdToken", cmdToken)
            ));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(Map.of(
                "code", 500,
                "message", "服务器内部错误：" + e.getMessage(),
                "data", null
            ));
        }
    }
}
