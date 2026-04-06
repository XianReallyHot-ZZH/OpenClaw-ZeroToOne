package com.openclaw.enterprise.scheduler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * CronPayload 反序列化器 — 根据 JSON 中的鉴别字段选择子类型
 *
 * <p>鉴别规则：</p>
 * <ul>
 *   <li>{@code "type"} == {@code "agent_turn"} → {@link CronPayload.AgentTurn}</li>
 *   <li>{@code "type"} == {@code "system_event"} → {@link CronPayload.SystemEvent}</li>
 * </ul>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 140-160 行 CronPayload 解析逻辑</p>
 */
public class CronPayloadDeserializer extends StdDeserializer<CronPayload> {

    public CronPayloadDeserializer() {
        super(CronPayload.class);
    }

    @Override
    public CronPayload deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        // 鉴别字段: "type" → "agent_turn" or "system_event"
        if (!node.has("type")) {
            throw new IllegalArgumentException(
                "Invalid payload format. Expected 'type' field. Got: " + node);
        }

        String type = node.get("type").asText();

        switch (type) {
            case "agent_turn" -> {
                String agentId = node.get("agentId").asText();
                String prompt = node.get("prompt").asText();
                return new CronPayload.AgentTurn(agentId, prompt);
            }
            case "system_event" -> {
                String message = node.get("message").asText();
                return new CronPayload.SystemEvent(message);
            }
            default -> throw new IllegalArgumentException(
                "Unknown payload type: " + type + ". Expected 'agent_turn' or 'system_event'.");
        }
    }
}
