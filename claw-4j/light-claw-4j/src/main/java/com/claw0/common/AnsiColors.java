package com.claw0.common;

/**
 * ANSI 终端颜色常量和格式化输出工具.
 *
 * <p>ANSI 转义码的通用格式是 {@code \033[<代码>m}, 其中代码对应不同的样式:
 * <ul>
 *   <li>{@code 0} = 重置所有样式 (每次着色后必须重置, 否则后续输出会继承样式)</li>
 *   <li>{@code 1} = 粗体</li>
 *   <li>{@code 2} = 暗淡</li>
 *   <li>{@code 3X} = 前景色 (31=红, 32=绿, 33=黄, 34=蓝, 35=紫, 36=青)</li>
 * </ul>
 *
 * <p>使用示例:
 * <pre>
 *   System.out.println(RED + "错误" + RESET);
 *   System.out.println(GREEN + BOLD + "成功" + RESET);
 * </pre>
 */
public final class AnsiColors {

    // ---- 基础样式 ----
    /** 重置所有样式 -- 每次着色后必须使用, 否则后续输出会继承之前的颜色 */
    public static final String RESET  = "\033[0m";
    /** 粗体 */
    public static final String BOLD   = "\033[1m";
    /** 暗淡 (降低亮度) */
    public static final String DIM    = "\033[2m";

    // ---- 前景色 ----
    public static final String CYAN   = "\033[36m";
    public static final String GREEN  = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED    = "\033[31m";
    public static final String BLUE   = "\033[34m";
    public static final String MAGENTA = "\033[35m";

    private AnsiColors() {}

    /** 返回带颜色的用户提示符字符串 */
    public static String coloredPrompt() {
        return CYAN + BOLD + "You > " + RESET;
    }

    /** 打印助手回复 (绿色标题 + 前后空行, 提升可读性) */
    public static void printAssistant(String text) {
        System.out.println("\n" + GREEN + BOLD + "Assistant:" + RESET + " " + text + "\n");
    }

    /** 打印工具调用信息 (黄色, 显示工具名和参数) */
    public static void printTool(String name, String details) {
        System.out.println(YELLOW + "  [tool: " + name + "] " + DIM + details + RESET);
    }

    /** 打印提示信息 (暗淡, 用于系统状态输出) */
    public static void printInfo(String text) {
        System.out.println(DIM + text + RESET);
    }

    /** 打印错误信息 (红色, 用于错误和警告) */
    public static void printError(String text) {
        System.out.println(RED + text + RESET);
    }
}
