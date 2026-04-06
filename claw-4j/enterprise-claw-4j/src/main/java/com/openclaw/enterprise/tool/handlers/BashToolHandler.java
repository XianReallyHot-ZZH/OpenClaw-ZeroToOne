package com.openclaw.enterprise.tool.handlers;

import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.tool.ToolDefinition;
import com.openclaw.enterprise.tool.ToolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bash 工具处理器 — 在工作目录中执行 bash 命令
 *
 * <p>功能：</p>
 * <ul>
 *   <li>在配置的工作目录中执行 bash 命令</li>
 *   <li>支持自定义超时时间 (默认 30 秒)</li>
 *   <li>自动合并 stdout 和 stderr 输出</li>
 *   <li>危险命令拦截 (rm -rf /, mkfs, dd, fork bomb 等)</li>
 *   <li>输出截断 (超过 50,000 字符自动截断)</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 第 118-146 行 tool_bash() 函数</p>
 */
@Component
public class BashToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(BashToolHandler.class);

    /** 最大输出字符数 — 超过此限制的输出将被截断 */
    private static final int MAX_OUTPUT = 50_000;

    /** 默认命令执行超时 (秒) */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 危险命令正则表达式集合
     *
     * <p>匹配以下模式的命令将被拒绝执行：</p>
     * <ul>
     *   <li>rm -rf / — 递归强制删除根目录</li>
     *   <li>mkfs — 格式化文件系统</li>
     *   <li>dd if= — 底层磁盘操作</li>
     *   <li>> /dev/sd — 直接写入磁盘设备</li>
     *   <li>:(){...} — fork bomb</li>
     *   <li>chmod -R 777 / — 递归修改根目录权限</li>
     * </ul>
     */
    private static final Set<Pattern> DANGEROUS_PATTERNS = Set.of(
        Pattern.compile("rm\\s+-rf\\s+/"),
        Pattern.compile("mkfs"),
        Pattern.compile("dd\\s+if="),
        Pattern.compile(">\\s*/dev/sd"),
        Pattern.compile(":\\(\\)\\s*\\{.*\\}"),     // fork bomb
        Pattern.compile("chmod\\s+-R\\s+777\\s+/")
    );

    /** 工具 Schema 定义 */
    private static final ToolDefinition SCHEMA = new ToolDefinition(
        "bash",
        "Execute a bash command in the working directory. "
            + "Supports timeout configuration. Stderr is merged with stdout. "
            + "Dangerous commands (rm -rf /, mkfs, etc.) are blocked.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "The bash command to execute."
                ),
                "timeout", Map.of(
                    "type", "integer",
                    "description", "Timeout in seconds. Default: 30."
                )
            ),
            "required", List.of("command")
        )
    );

    /** 工作目录 — 从 WorkspaceProperties 注入 */
    private final Path workDir;

    /**
     * 构造 Bash 工具处理器
     *
     * @param workspaceProps 工作空间配置属性
     */
    public BashToolHandler(AppProperties.WorkspaceProperties workspaceProps) {
        this.workDir = workspaceProps.path();
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public ToolDefinition getSchema() {
        return SCHEMA;
    }

    /**
     * 执行 bash 命令
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>提取 command 和 timeout 参数</li>
     *   <li>安全检查 — 拦截危险命令</li>
     *   <li>通过 ProcessBuilder 执行命令</li>
     *   <li>读取输出 (stdout + stderr 合并)</li>
     *   <li>超时处理</li>
     *   <li>输出截断</li>
     * </ol>
     *
     * @param input 工具调用参数 (必须包含 "command"，可选 "timeout")
     * @return 命令执行结果文本
     */
    @Override
    public String execute(Map<String, Object> input) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return "Error: 'command' parameter is required.";
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (input.get("timeout") instanceof Number n) {
            timeout = n.intValue();
        }

        // 安全检查 — 拦截危险命令
        if (!isSafeCommand(command)) {
            log.warn("Dangerous command blocked: {}", command);
            return "Error: Dangerous command blocked: " + command;
        }

        try {
            // 构建并执行进程
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);  // 合并 stderr 到 stdout

            Process proc = pb.start();

            // 读取输出
            String output = new String(proc.getInputStream().readAllBytes());

            // 等待完成，带超时
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                // 超时 — 强制终止进程
                proc.destroyForcibly();
                log.warn("Command timed out after {}s: {}", timeout, command);
                return "Error: Command timed out after " + timeout + "s";
            }

            // 非零退出码 — 附加退出码信息
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                output = truncate(output) + "\n[exit code: " + exitCode + "]";
                return output;
            }

            // 空输出处理
            if (output.isBlank()) {
                return "[no output]";
            }

            return truncate(output);

        } catch (Exception e) {
            log.error("Bash execution failed: {}", command, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 检查命令是否安全
     *
     * <p>遍历危险命令正则集合，如果命令匹配任一模式则判定为危险命令。</p>
     *
     * @param command 要检查的命令
     * @return 如果命令安全返回 true
     */
    private boolean isSafeCommand(String command) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 截断过长的输出
     *
     * <p>超过 {@link #MAX_OUTPUT} 字符的输出将被截断，
     * 并附加截断提示信息。</p>
     *
     * @param text 原始输出文本
     * @return 截断后的文本
     */
    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT)
            + "\n... [truncated, " + text.length() + " total chars]";
    }
}
