package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.agent.AgentConfig;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Agent 配置持久化存储 — JSONL 格式的 Agent 配置持久层
 *
 * <p>启动时从 {@code workspace/agents.jsonl} 加载 Agent 配置，
 * 注册/注销时同步更新文件。</p>
 */
@Service
public class AgentStore {

    private static final Logger log = LoggerFactory.getLogger(AgentStore.class);

    private static final String STORE_FILE = "agents.jsonl";

    private final Path storePath;
    private final AgentManager agentManager;

    /**
     * 构造 Agent 存储服务
     *
     * @param workspaceProps 工作空间配置
     * @param agentManager   Agent 管理器
     */
    public AgentStore(AppProperties.WorkspaceProperties workspaceProps,
                      AgentManager agentManager) {
        this.storePath = workspaceProps.path().resolve(STORE_FILE);
        this.agentManager = agentManager;
    }

    /**
     * 启动时加载 Agent 配置
     */
    @PostConstruct
    void loadAgents() {
        if (!Files.exists(storePath)) {
            log.info("No agents file found at {}", storePath);
            return;
        }

        List<AgentConfig> agents = JsonUtils.readJsonl(storePath, AgentConfig.class);
        for (AgentConfig config : agents) {
            try {
                agentManager.register(config);
            } catch (Exception e) {
                log.warn("Failed to load agent {}: {}", config.id(), e.getMessage());
            }
        }
        log.info("Loaded {} agents from {}", agents.size(), storePath);
    }

    /**
     * 注册 Agent 并持久化
     *
     * @param config Agent 配置
     */
    public void registerAndPersist(AgentConfig config) {
        agentManager.register(config);
        try {
            JsonUtils.appendJsonl(storePath, config);
        } catch (Exception e) {
            log.error("Failed to persist agent: {}", config.id(), e);
        }
    }

    /**
     * 注销 Agent 并重写文件
     *
     * @param agentId Agent ID
     * @return 是否成功
     */
    public boolean unregisterAndPersist(String agentId) {
        try {
            agentManager.unregister(agentId);
            rewriteStore();
            return true;
        } catch (Exception e) {
            log.error("Failed to unregister agent: {}", agentId, e);
            return false;
        }
    }

    /**
     * 全量重写 JSONL 文件
     */
    private void rewriteStore() {
        try {
            List<AgentConfig> all = agentManager.listAgents();
            String content = all.stream()
                .map(JsonUtils::toJson)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
            if (!content.isEmpty()) {
                content += "\n";
            }
            Files.writeString(storePath, content);
        } catch (Exception e) {
            log.error("Failed to rewrite agents store", e);
        }
    }
}
