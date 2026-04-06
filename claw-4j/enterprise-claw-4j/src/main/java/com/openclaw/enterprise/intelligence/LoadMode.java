package com.openclaw.enterprise.intelligence;

/**
 * 加载模式枚举 — 控制工作空间文件的加载范围
 *
 * <ul>
 *   <li>{@link #FULL} — 加载全部 8 个工作空间文件 (主 Agent)</li>
 *   <li>{@link #MINIMAL} — 仅加载 AGENTS.md + TOOLS.md (子 Agent/Cron)</li>
 *   <li>{@link #NONE} — 不加载任何文件 (裸模式)</li>
 * </ul>
 */
public enum LoadMode {
    FULL,
    MINIMAL,
    NONE
}
