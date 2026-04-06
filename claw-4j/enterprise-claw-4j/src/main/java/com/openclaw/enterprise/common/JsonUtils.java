package com.openclaw.enterprise.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSON 工具类 — 全局唯一的 Jackson ObjectMapper 封装
 *
 * <p>提供统一的 JSON 序列化/反序列化能力，包括：</p>
 * <ul>
 *   <li>对象 → JSON 字符串</li>
 *   <li>JSON 字符串 → 对象</li>
 *   <li>JSONL 追加写入</li>
 *   <li>JSONL 批量读取</li>
 * </ul>
 *
 * <p>claw0 参考: s03_sessions.py 中所有 json.dumps() / json.loads() 调用</p>
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    /**
     * 全局共享的 ObjectMapper 实例
     *
     * <p>配置说明：</p>
     * <ul>
     *   <li>JavaTimeModule — 支持 Java 8 日期时间类型</li>
     *   <li>禁用 WRITE_DATES_AS_TIMESTAMPS — 日期序列化为 ISO-8601 字符串</li>
     *   <li>NON_NULL — 跳过 null 值字段</li>
     *   <li>FAIL_ON_UNKNOWN_PROPERTIES=false — 忽略未知字段，增强兼容性</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {}  // 工具类，禁止实例化

    /**
     * 获取底层 ObjectMapper 实例
     *
     * <p>在需要自定义序列化/反序列化时使用。</p>
     *
     * @return 共享的 ObjectMapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 将任意对象序列化为 JSON 字符串
     *
     * <p>claw0 对应: json.dumps(obj, ensure_ascii=False)</p>
     *
     * @param obj 要序列化的对象
     * @return JSON 字符串
     * @throws RuntimeException 如果序列化失败
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象
     *
     * <p>claw0 对应: json.loads(text)</p>
     *
     * @param json   JSON 字符串
     * @param clazz  目标类型
     * @param <T>    目标类型参数
     * @return 反序列化后的对象
     * @throws RuntimeException 如果反序列化失败
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed for " + clazz.getSimpleName(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为泛型类型对象
     *
     * <p>用于反序列化 List、Map 等泛型容器，例如：</p>
     * <pre>
     * List&lt;String&gt; names = JsonUtils.fromJson(json, new TypeReference&lt;&gt;() {});
     * </pre>
     *
     * @param json    JSON 字符串
     * @param typeRef 目标泛型类型引用
     * @param <T>     目标类型参数
     * @return 反序列化后的对象
     * @throws RuntimeException 如果反序列化失败
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed for " + typeRef.getType(), e);
        }
    }

    /**
     * 向 JSONL 文件追加一条记录
     *
     * <p>JSONL (JSON Lines) 格式: 每行一个 JSON 对象。
     * 用于会话持久化、记忆日志等追加写入场景。</p>
     *
     * <p>claw0 对应: s03_sessions.py 中 open(path, "a") + json.dumps(record)</p>
     *
     * <p>注意: 使用 CREATE + APPEND 打开模式，文件不存在时自动创建。</p>
     *
     * @param file  JSONL 文件路径
     * @param obj   要追加的对象
     */
    public static void appendJsonl(Path file, Object obj) {
        try {
            String line = toJson(obj) + "\n";
            Files.writeString(file, line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException("JSONL append failed: " + file, e);
        }
    }

    /**
     * 读取 JSONL 文件中的所有记录
     *
     * <p>逐行读取并反序列化，空行跳过，格式错误行记录警告日志并跳过。</p>
     *
     * <p>claw0 对应: s03_sessions.py 中 text.splitlines() + json.loads()</p>
     *
     * @param file  JSONL 文件路径
     * @param clazz 每行记录的目标类型
     * @param <T>   目标类型参数
     * @return 反序列化后的记录列表，文件不存在时返回空列表
     */
    public static <T> List<T> readJsonl(Path file, Class<T> clazz) {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try {
            List<String> lines = Files.readAllLines(file);
            List<T> result = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;  // 跳过空行
                }
                try {
                    result.add(MAPPER.readValue(line, clazz));
                } catch (Exception e) {
                    // 格式错误行记录警告但继续处理后续行
                    log.warn("Malformed JSONL at {}:{} — {}", file, i + 1, e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("JSONL read failed: " + file, e);
        }
    }
}
