package com.openclaw.enterprise.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 文件工具类 — 提供原子写入、安全路径解析等文件操作
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>原子写入 — tmp + fsync + atomic move，保证数据完整性</li>
 *   <li>安全路径解析 — 防止路径遍历攻击 (path traversal)</li>
 *   <li>行追加 — 向文件追加一行文本</li>
 * </ul>
 *
 * <p>claw0 参考:</p>
 * <ul>
 *   <li>s08_delivery.py 第 200-230 行 _atomic_write()</li>
 *   <li>s02_tool_use.py 中 safe_path() 函数</li>
 * </ul>
 */
public final class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {}  // 工具类，禁止实例化

    /**
     * 原子写入 — 先写临时文件，再 fsync，最后原子移动
     *
     * <p>写入流程：</p>
     * <ol>
     *   <li>在目标文件同级目录创建随机命名的临时文件</li>
     *   <li>将内容写入临时文件</li>
     *   <li>调用 fsync 确保数据落盘</li>
     *   <li>原子性地将临时文件移动到目标路径</li>
     *   <li>如果移动失败，清理临时文件</li>
     * </ol>
     *
     * <p>claw0 对应: s08_delivery.py _atomic_write() — tmp + flush + os.replace()</p>
     *
     * @param target  目标文件路径
     * @param content 要写入的内容
     * @throws IOException 如果写入过程中发生 I/O 错误
     */
    public static void writeAtomically(Path target, String content) throws IOException {
        // 在同级目录创建随机命名的临时文件，确保同一文件系统，支持原子 move
        Path tmp = target.resolveSibling(".tmp." + UUID.randomUUID());
        try {
            Files.writeString(tmp, content);

            // fsync 确保数据真正写入磁盘（而非仅 OS 缓冲区）
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }

            // 原子移动 — 在同一文件系统上是原子操作
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            // 无论成功与否，清理可能残留的临时文件
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 向文件追加一行文本
     *
     * <p>自动在文本末尾添加换行符。文件不存在时自动创建。</p>
     *
     * @param file 目标文件路径
     * @param line 要追加的一行文本（不需要包含换行符）
     * @throws IOException 如果写入过程中发生 I/O 错误
     */
    public static void appendLine(Path file, String line) throws IOException {
        Files.writeString(file, line + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }

    /**
     * 安全路径解析 — 基于 base 目录解析相对路径，防止路径遍历
     *
     * <p>将相对路径解析为基于 base 的绝对路径，并检查解析后的路径
     * 是否仍在 base 目录内。如果路径尝试跳出 base 目录（如包含 ../），
     * 则抛出 SecurityException。</p>
     *
     * <p>claw0 对应: s02_tool_use.py safe_path() 函数</p>
     *
     * <p>使用示例：</p>
     * <pre>
     * // 正常路径
     * safePath(Path.of("/workspace"), "memory/daily/log.md")
     * // → /workspace/memory/daily/log.md
     *
     * // 路径遍历攻击 — 抛出异常
     * safePath(Path.of("/workspace"), "../../etc/passwd")
     * // → SecurityException: Path traversal detected
     * </pre>
     *
     * @param base      基础目录（通常是 workspace 根目录）
     * @param relative  相对路径字符串
     * @return 解析后的安全路径
     * @throws SecurityException 如果检测到路径遍历攻击
     */
    public static Path safePath(Path base, String relative) {
        // resolve + normalize 处理 .. 和 . 组件
        Path resolved = base.resolve(relative).normalize();

        // 检查解析后的路径是否仍在 base 目录内
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path traversal detected: " + relative);
        }
        return resolved;
    }
}
