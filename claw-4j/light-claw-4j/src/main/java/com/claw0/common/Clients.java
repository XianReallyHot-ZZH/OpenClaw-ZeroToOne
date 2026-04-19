package com.claw0.common;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

/**
 * Anthropic API 客户端工厂.
 *
 * <p>通过 {@link Config} 读取 ANTHROPIC_API_KEY 和 ANTHROPIC_BASE_URL,
 * 确保无论配置来源是 .env 文件、环境变量还是 JVM 系统属性, 客户端都能正确初始化.
 *
 * <p>不使用 SDK 自带的 {@code fromEnv()}, 因为它只读 {@code System.getenv()},
 * 无法获取 dotenv 库加载的 .env 文件中的值.
 */
public final class Clients {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private Clients() {}

    /** 使用 Config 中的 ANTHROPIC_API_KEY 和 ANTHROPIC_BASE_URL 创建客户端. */
    public static AnthropicClient create() {
        return create(Config.get("ANTHROPIC_API_KEY"));
    }

    /** 使用指定的 API Key 和 Config 中的 ANTHROPIC_BASE_URL 创建客户端. */
    public static AnthropicClient create(String apiKey) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(Config.get("ANTHROPIC_BASE_URL", DEFAULT_BASE_URL))
                .build();
    }
}
