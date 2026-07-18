package com.ai.aicommunity.config;

import com.ai.aicommunity.websocket.ChatWebSocketHandler;
import com.ai.aicommunity.websocket.NotificationWebSocketHandler;
import com.ai.aicommunity.websocket.NotificationWebSocketHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final NotificationWebSocketHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(NotificationWebSocketHandler notificationWebSocketHandler,
                           ChatWebSocketHandler chatWebSocketHandler,
                           NotificationWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.notificationWebSocketHandler = notificationWebSocketHandler;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/ws/notifications")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
