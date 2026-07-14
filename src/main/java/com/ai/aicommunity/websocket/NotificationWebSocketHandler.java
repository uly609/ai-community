package com.ai.aicommunity.websocket;

import com.ai.aicommunity.dto.NotificationPushMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public NotificationWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        if (userId != null) {
            userSessions.computeIfAbsent(userId, ignored -> new CopyOnWriteArraySet<>()).add(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeSession(session);
    }

    public void push(Long userId, NotificationPushMessage message) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    removeSession(session);
                    continue;
                }
                synchronized (session) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            sessions.removeIf(session -> !session.isOpen());
        }
    }

    private void removeSession(WebSocketSession session) {
        Long userId = getUserId(session);
        if (userId == null) {
            return;
        }
        userSessions.computeIfPresent(userId, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    private Long getUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(NotificationWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        return userId instanceof Long value ? value : null;
    }
}
