package com.openclaw.enterprise.scheduler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.Instant;

/**
 * CronSchedule 反序列化器 — 根据 JSON 中的鉴别字段选择子类型
 *
 * <p>鉴别规则：</p>
 * <ul>
 *   <li>包含 {@code "at"} 字段 → {@link CronSchedule.At}</li>
 *   <li>包含 {@code "every"} 字段 → {@link CronSchedule.Every}</li>
 *   <li>包含 {@code "cron"} 字段 → {@link CronSchedule.CronExpression}</li>
 * </ul>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 100-130 行 schedule 解析逻辑</p>
 */
public class CronScheduleDeserializer extends StdDeserializer<CronSchedule> {

    public CronScheduleDeserializer() {
        super(CronSchedule.class);
    }

    @Override
    public CronSchedule deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        // 鉴别字段: "at" → At, "every" → Every, "cron" → CronExpression
        if (node.has("at")) {
            Instant datetime = Instant.parse(node.get("at").asText());
            return new CronSchedule.At(datetime);
        }
        if (node.has("every")) {
            int intervalSeconds = node.get("every").asInt();
            return new CronSchedule.Every(intervalSeconds);
        }
        if (node.has("cron")) {
            String expression = node.get("cron").asText();
            return new CronSchedule.CronExpression(expression);
        }

        throw new IllegalArgumentException(
            "Invalid schedule format. Expected 'at', 'every', or 'cron' field. Got: " + node);
    }
}
