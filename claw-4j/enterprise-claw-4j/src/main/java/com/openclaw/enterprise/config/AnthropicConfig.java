package com.openclaw.enterprise.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openclaw.enterprise.config.AppProperties.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Anthropic 客户端配置
 *
 * <p>构建并注册 AnthropicClient Bean，使用主 Profile 的 API 密钥。
 * 支持 Sprint 6 的 ProfileManager 为每个 Profile 独立创建客户端。</p>
 *
 * <p>claw0 参考: s01_agent_loop.py 第 10-15 行 Anthropic(api_key=...)</p>
 */
@Configuration
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    /**
     * 创建主要的 AnthropicClient Bean
     *
     * <p>使用 Anthropic Java SDK 的 AnthropicOkHttpClient 构建。
     * 如果配置了 base-url，则使用自定义端点 (用于代理或兼容 API)。</p>
     *
     * @param props Anthropic 配置属性
     * @return AnthropicClient 实例
     */
    @Bean
    @Primary
    public AnthropicClient anthropicClient(AnthropicProperties props) {
        var primary = props.profiles().getFirst();

        var builder = AnthropicOkHttpClient.builder()
            .apiKey(primary.apiKey());

        // 如果配置了自定义 base-url，则使用
        if (primary.baseUrl() != null && !primary.baseUrl().isBlank()) {
            builder.baseUrl(primary.baseUrl());
            log.info("AnthropicClient using custom base URL: {}", primary.baseUrl());
        }

        log.info("AnthropicClient created with model: {}", props.modelId());
        return builder.build();
    }
}
