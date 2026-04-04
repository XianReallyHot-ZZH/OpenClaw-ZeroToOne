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
 * Jackson ObjectMapper singleton with convenience methods for JSON and JSONL.
 */
public final class JsonUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtils() {}

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed for " + type.getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON to Map failed", e);
        }
    }

    public static void appendJsonl(Path file, Object record) throws IOException {
        Files.createDirectories(file.getParent());
        String line = toJson(record) + "\n";
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static List<Map<String, Object>> readJsonl(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                records.add(MAPPER.readValue(line, Map.class));
            }
        }
        return records;
    }
}
