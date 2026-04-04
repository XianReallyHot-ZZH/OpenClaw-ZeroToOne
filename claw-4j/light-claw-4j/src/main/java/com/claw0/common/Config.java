package com.claw0.common;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Loads .env configuration and provides typed accessors.
 */
public final class Config {

    private static final Dotenv DOTENV = Dotenv.configure()
            .directory(findProjectRoot())
            .ignoreIfMissing()
            .load();

    private Config() {}

    public static String get(String key) {
        // Check system properties / env vars first, then .env
        String value = System.getProperty(key);
        if (value != null) return value;
        value = System.getenv(key);
        if (value != null) return value;
        return DOTENV.get(key);
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    private static String findProjectRoot() {
        // Walk up from CWD to find directory containing pom.xml
        var dir = System.getProperty("user.dir");
        while (dir != null) {
            if (java.nio.file.Path.of(dir, "pom.xml").toFile().exists()) {
                return dir;
            }
            var parent = java.nio.file.Path.of(dir).getParent();
            dir = parent != null ? parent.toString() : null;
        }
        return ".";
    }
}
