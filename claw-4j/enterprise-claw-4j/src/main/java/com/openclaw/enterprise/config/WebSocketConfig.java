package com.openclaw.enterprise.config;

import com.openclaw.enterprise.gateway.GatewayWebSocketHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(AppProperties.CorsProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    private final AppProperties.CorsProperties corsProperties;
    private final GatewayWebSocketHandler handler;

    public WebSocketConfig(AppProperties.CorsProperties corsProperties, GatewayWebSocketHandler handler) {
        this.corsProperties = corsProperties;
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/gateway")
            .setAllowedOrigins(corsProperties.effectiveOrigins().toArray(new String[0]));
    }

}
