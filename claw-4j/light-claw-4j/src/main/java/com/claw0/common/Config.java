package com.claw0.common;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * 配置加载器 -- 从 .env 文件和环境变量中读取配置项.
 *
 * <p>配置优先级 (从高到低):
 * <ol>
 *   <li>JVM 系统属性 ({@code -Dkey=value})</li>
 *   <li>操作系统环境变量</li>
 *   <li>.env 文件中的值</li>
 * </ol>
 *
 * <p>这个优先级设计保证了生产环境中可以通过环境变量覆盖 .env 文件的默认值,
 * 同时开发时可以方便地使用 .env 文件管理本地配置.
 *
 * <p>使用方法:
 * <pre>
 *   Config.get("ANTHROPIC_API_KEY")                // 必须存在, 否则返回 null
 *   Config.get("MODEL_ID", "claude-sonnet-4-20250514")  // 带默认值, 永远不会返回 null
 * </pre>
 */
public final class Config {

    /** Dotenv 单例: 从项目根目录的 .env 文件加载配置. ignoreIfMissing 确保无 .env 时不报错. */
    private static final Dotenv DOTENV = Dotenv.configure()
            .directory(findProjectRoot())
            .ignoreIfMissing()    // 没有 .env 文件时不抛异常, 依赖环境变量即可
            .load();

    private Config() {}

    /**
     * 按优先级获取配置值: 系统属性 > 环境变量 > .env 文件.
     *
     * @param key 配置键名
     * @return 配置值, 所有来源都没有时返回 null
     */
    public static String get(String key) {
        // 1. 优先检查 JVM 系统属性 (如 -DANTHROPIC_API_KEY=xxx)
        String value = System.getProperty(key);
        if (value != null) return value;
        // 2. 其次检查操作系统环境变量
        value = System.getenv(key);
        if (value != null) return value;
        // 3. 最后从 .env 文件读取
        return DOTENV.get(key);
    }

    /**
     * 获取配置值, 支持默认值. 任何情况下都不会返回 null.
     *
     * @param key          配置键名
     * @param defaultValue 默认值 (配置项不存在时使用)
     * @return 配置值或默认值
     */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 从当前工作目录向上查找包含 pom.xml 的目录作为项目根目录.
     * 这保证了无论从哪个子目录运行, 都能找到正确的 .env 文件.
     *
     * @return 项目根目录路径, 找不到时返回 "."
     */
    private static String findProjectRoot() {
        var dir = System.getProperty("user.dir");
        while (dir != null) {
            if (java.nio.file.Path.of(dir, "pom.xml").toFile().exists()) {
                return dir;
            }
            var parent = java.nio.file.Path.of(dir).getParent();
            dir = parent != null ? parent.toString() : null;
        }
        return ".";   // 回退: 在当前目录下寻找 .env
    }
}
