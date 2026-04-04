package com.claw0.common;

/**
 * ANSI terminal color constants and print helpers.
 */
public final class AnsiColors {

    public static final String RESET  = "\033[0m";
    public static final String BOLD   = "\033[1m";
    public static final String DIM    = "\033[2m";
    public static final String CYAN   = "\033[36m";
    public static final String GREEN  = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED    = "\033[31m";
    public static final String BLUE   = "\033[34m";
    public static final String MAGENTA = "\033[35m";

    private AnsiColors() {}

    public static String coloredPrompt() {
        return CYAN + BOLD + "You > " + RESET;
    }

    public static void printAssistant(String text) {
        System.out.println("\n" + GREEN + BOLD + "Assistant:" + RESET + " " + text + "\n");
    }

    public static void printTool(String name, String details) {
        System.out.println(YELLOW + "  [tool: " + name + "] " + DIM + details + RESET);
    }

    public static void printInfo(String text) {
        System.out.println(DIM + text + RESET);
    }

    public static void printError(String text) {
        System.out.println(RED + text + RESET);
    }
}
