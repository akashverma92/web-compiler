package com.webcompiler.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CompilerWebSocketHandler handler;

    public WebSocketConfig(CompilerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(handler, "/ws/run")
            // Loosen for local dev; in prod, restrict to your domains.
            .setAllowedOriginPatterns(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "*"
            );
    }
}
