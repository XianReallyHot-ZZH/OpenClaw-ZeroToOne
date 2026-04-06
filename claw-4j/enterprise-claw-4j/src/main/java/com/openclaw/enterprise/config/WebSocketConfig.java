package com.openclaw.enterprise.config;

import com.openclaw.enterprise.gateway.GatewayWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置 — 注册 JSON-RPC 2.0 网关端点
 *
 * <p>端点路径: {@code ws://localhost:8080/ws/gateway}</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中 WebSocket 服务器配置</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gatewayWebSocketHandler(), "/ws/gateway")
            .setAllowedOrigins("*");
    }

    @Bean
    public GatewayWebSocketHandler gatewayWebSocketHandler() {
        return new GatewayWebSocketHandler();
    }
}
