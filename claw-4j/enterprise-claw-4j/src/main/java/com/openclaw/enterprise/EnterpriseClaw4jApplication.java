package com.openclaw.enterprise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * enterprise-claw-4j 应用入口
 *
 * <p>企业级 Java Agent 框架，基于 Spring Boot 3.5 + Anthropic Claude API。
 * 支持 WebSocket/REST 网关、多渠道接入 (CLI/Telegram/飞书)、
 * 会话持久化、智能记忆、定时任务、韧性容错等企业级特性。</p>
 */
@SpringBootApplication
public class EnterpriseClaw4jApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseClaw4jApplication.class, args);
    }
}
