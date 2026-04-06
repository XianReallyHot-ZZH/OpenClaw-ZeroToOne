package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 绑定规则持久化存储 — JSONL 格式的绑定规则持久层
 *
 * <p>在应用启动时从 {@code workspace/bindings.jsonl} 加载所有绑定规则，
 * 添加/删除时同步更新文件。</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中绑定规则的文件存储</p>
 */
@Service
public class BindingStore {

    private static final Logger log = LoggerFactory.getLogger(BindingStore.class);

    private static final String STORE_FILE = "bindings.jsonl";

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Path storePath;
    private final BindingTable bindingTable;

    /**
     * 构造绑定规则存储
     *
     * @param workspaceProps 工作空间配置
     * @param bindingTable   路由表 (写入目标)
     */
    public BindingStore(AppProperties.WorkspaceProperties workspaceProps,
                        BindingTable bindingTable) {
        this.storePath = workspaceProps.path().resolve(STORE_FILE);
        this.bindingTable = bindingTable;
    }

    /**
     * 启动时加载绑定规则
     */
    @PostConstruct
    void loadBindings() {
        if (!Files.exists(storePath)) {
            log.info("No bindings file found at {}", storePath);
            return;
        }

        List<Binding> bindings = JsonUtils.readJsonl(storePath, Binding.class);
        for (Binding b : bindings) {
            bindingTable.addBinding(b);
        }
        log.info("Loaded {} bindings from {}", bindings.size(), storePath);
    }

    /**
     * 添加绑定并持久化
     *
     * @param binding 绑定规则
     */
    public void addAndPersist(Binding binding) {
        bindingTable.addBinding(binding);
        appendToStore(binding);
    }

    /**
     * 移除绑定并重写文件
     *
     * @param tier 层级
     * @param key  匹配键
     * @return 是否成功移除
     */
    public boolean removeAndPersist(int tier, String key) {
        boolean removed = bindingTable.removeBinding(tier, key);
        if (removed) {
            rewriteStore();
        }
        return removed;
    }

    /**
     * 追加一条绑定到 JSONL 文件
     */
    private void appendToStore(Binding binding) {
        writeLock.lock();
        try {
            JsonUtils.appendJsonl(storePath, binding);
        } catch (Exception e) {
            log.error("Failed to persist binding", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 全量重写 JSONL 文件 (删除操作后)
     */
    private void rewriteStore() {
        writeLock.lock();
        try {
            List<Binding> all = bindingTable.listBindings();
            String content = all.stream()
                .map(JsonUtils::toJson)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
            if (!content.isEmpty()) {
                content += "\n";
            }
            Files.writeString(storePath, content);
        } catch (Exception e) {
            log.error("Failed to rewrite bindings store", e);
        } finally {
            writeLock.unlock();
        }
    }
}
