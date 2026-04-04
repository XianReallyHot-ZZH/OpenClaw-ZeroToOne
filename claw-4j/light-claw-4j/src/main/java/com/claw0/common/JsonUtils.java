package com.claw0.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jackson ObjectMapper 单例, 提供 JSON 和 JSONL 的便捷方法.
 *
 * <p>配置说明:
 * <ul>
 *   <li>{@link ParameterNamesModule} -- 支持 Java record 的构造函数参数名自动推断,
 *       无需 @JsonProperty 注解即可反序列化 record 类型</li>
 *   <li>{@link JavaTimeModule} -- 支持 Java 8+ 日期时间类型 (Instant, LocalDateTime 等)</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS = false} -- 日期序列化为 ISO-8601 字符串
 *       (如 "2024-01-01T00:00:00Z") 而非数值时间戳, 提升可读性</li>
 * </ul>
 *
 * <p>JSONL (JSON Lines) 格式: 每行一个独立的 JSON 对象, 用于追加写入场景 (如会话历史).
 * 相比普通 JSON 数组, JSONL 支持原子追加而无需读取-修改-写入整个文件.
 */
public final class JsonUtils {

    /** 全局共享的 ObjectMapper 实例. Jackson 的 ObjectMapper 是线程安全的, 可以全局复用. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new ParameterNamesModule())   // 支持 record 无注解反序列化
            .registerModule(new JavaTimeModule())          // 支持 Java 时间类型
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);  // 日期用 ISO-8601 字符串

    private JsonUtils() {}

    /**
     * 将任意对象序列化为 JSON 字符串.
     *
     * @param value 要序列化的对象
     * @return JSON 字符串
     * @throws RuntimeException 序列化失败时包装为运行时异常 (避免调用方必须处理受检异常)
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象.
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @return 反序列化后的对象
     * @throws RuntimeException 反序列化失败时包装为运行时异常
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed for " + type.getSimpleName(), e);
        }
    }

    /**
     * 将 JSON 字符串解析为 Map (适用于结构不确定的 JSON).
     *
     * @param json JSON 字符串
     * @return 键值对 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON to Map failed", e);
        }
    }

    /**
     * 将一条记录追加写入 JSONL 文件 (每行一个 JSON 对象).
     * 如果文件不存在则自动创建, 如果父目录不存在也自动创建.
     *
     * @param file   JSONL 文件路径
     * @param record 要写入的记录对象
     * @throws IOException 写入失败时抛出
     */
    public static void appendJsonl(Path file, Object record) throws IOException {
        Files.createDirectories(file.getParent());
        String line = toJson(record) + "\n";
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 读取 JSONL 文件中的所有记录, 每行解析为一个 Map.
     * 空行会被自动跳过.
     *
     * @param file JSONL 文件路径
     * @return 记录列表, 文件不存在时返回空列表
     * @throws IOException 读取失败时抛出
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readJsonl(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;   // 跳过空行
            records.add(MAPPER.readValue(line, Map.class));
        }
        return records;
    }
}
