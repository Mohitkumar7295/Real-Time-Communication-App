package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Spring WebSocket configuration registering the plain SignalingWebSocketHandler at /ws.
 * Origin verification is configured from app.allowed-origin.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingWebSocketHandler signalingWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final AppProperties appProperties;

    public WebSocketConfig(SignalingWebSocketHandler signalingWebSocketHandler,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           AppProperties appProperties) {
        this.signalingWebSocketHandler = signalingWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.appProperties = appProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingWebSocketHandler, "/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(appProperties.getAllowedOrigin());
    }
}
