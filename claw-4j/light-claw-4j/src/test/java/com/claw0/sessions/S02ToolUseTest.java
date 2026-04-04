package com.claw0.sessions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S02ToolUseTest {

    // ================================================================
    // safePath tests
    // ================================================================

    @Test
    void safePath_normalRelativePath_resolvesWithinWorkdir() {
        // A simple relative path should resolve inside WORKDIR
        Path result = S02ToolUse.safePath("test.txt");
        assertTrue(result.startsWith(S02ToolUse.WORKDIR.toAbsolutePath().normalize()));
    }

    @Test
    void safePath_pathTraversal_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> S02ToolUse.safePath("../../etc/passwd"));
    }

    @Test
    void safePath_absolutePathOutsideWorkdir_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> S02ToolUse.safePath("/etc/passwd"));
    }

    @Test
    void safePath_nestedRelativePath_ok() {
        Path result = S02ToolUse.safePath("workspace/skills/test/foo.txt");
        assertTrue(result.startsWith(S02ToolUse.WORKDIR.toAbsolutePath().normalize()));
        assertEquals(S02ToolUse.WORKDIR.resolve("workspace/skills/test/foo.txt").toAbsolutePath().normalize(),
                result);
    }

    @Test
    void safePath_dotdotWithinWorkdir_ok() {
        // "foo/../bar" is still within workdir
        Path result = S02ToolUse.safePath("foo/../bar.txt");
        assertEquals(S02ToolUse.WORKDIR.resolve("bar.txt").toAbsolutePath().normalize(), result);
    }

    // ================================================================
    // truncate tests
    // ================================================================

    @Test
    void truncate_shortText_unchanged() {
        assertEquals("hello", S02ToolUse.truncate("hello", 100));
    }

    @Test
    void truncate_exactLength_unchanged() {
        String text = "a".repeat(10);
        assertEquals(text, S02ToolUse.truncate(text, 10));
    }

    @Test
    void truncate_exceedsLimit_truncated() {
        String text = "a".repeat(20);
        String result = S02ToolUse.truncate(text, 10);
        assertTrue(result.startsWith("a".repeat(10)));
        assertTrue(result.contains("[truncated"));
        assertTrue(result.contains("20 total chars"));
    }

    // ================================================================
    // toolBash -- dangerous command rejection
    // ================================================================

    @Test
    void toolBash_dangerousRmRf_rejected() {
        String result = S02ToolUse.toolBash(Map.of("command", "rm -rf /"));
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("dangerous"));
    }

    @Test
    void toolBash_dangerousMkfs_rejected() {
        String result = S02ToolUse.toolBash(Map.of("command", "mkfs /dev/sda1"));
        assertTrue(result.contains("Error"));
    }

    @Test
    void toolBash_dangerousDd_rejected() {
        String result = S02ToolUse.toolBash(Map.of("command", "dd if=/dev/zero of=/dev/sda"));
        assertTrue(result.contains("Error"));
    }

    @Test
    void toolBash_safeCommand_executes() {
        String result = S02ToolUse.toolBash(Map.of("command", "echo hello"));
        assertFalse(result.contains("Error: Refused"));
        assertTrue(result.contains("hello"));
    }

    @Test
    void toolBash_timeout_exceeded() {
        String result = S02ToolUse.toolBash(Map.of("command", "sleep 60", "timeout", 1));
        assertTrue(result.contains("timed out"));
    }

    // ================================================================
    // toolWriteFile / toolReadFile / toolEditFile
    // ================================================================

    @Test
    void toolWriteAndRead_roundtrip(@TempDir Path tempDir) throws IOException {
        // Override WORKDIR by creating a file inside workspace
        String relPath = "test-ws/write_test.txt";
        Path target = S02ToolUse.WORKDIR.resolve(relPath);
        Files.createDirectories(target.getParent());

        String writeResult = S02ToolUse.toolWriteFile(Map.of(
                "file_path", relPath,
                "content", "Hello, World!"));
        assertTrue(writeResult.contains("Successfully wrote"));

        String readResult = S02ToolUse.toolReadFile(Map.of("file_path", relPath));
        assertEquals("Hello, World!", readResult);

        // Cleanup
        Files.deleteIfExists(target);
    }

    @Test
    void toolEditFile_uniqueReplace_succeeds(@TempDir Path tempDir) throws IOException {
        String relPath = "test-ws/edit_test.txt";
        Path target = S02ToolUse.WORKDIR.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "foo bar baz");

        String result = S02ToolUse.toolEditFile(Map.of(
                "file_path", relPath,
                "old_string", "bar",
                "new_string", "BAR"));
        assertTrue(result.contains("Successfully edited"));
        assertEquals("foo BAR baz", Files.readString(target));

        Files.deleteIfExists(target);
    }

    @Test
    void toolEditFile_nonUniqueMatch_fails() throws IOException {
        String relPath = "test-ws/edit_multi.txt";
        Path target = S02ToolUse.WORKDIR.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "aaa bbb aaa");

        String result = S02ToolUse.toolEditFile(Map.of(
                "file_path", relPath,
                "old_string", "aaa",
                "new_string", "ccc"));
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("2 times"));

        Files.deleteIfExists(target);
    }

    @Test
    void toolEditFile_notFound_fails() {
        String result = S02ToolUse.toolEditFile(Map.of(
                "file_path", "nonexistent_file_xyz.txt",
                "old_string", "foo",
                "new_string", "bar"));
        assertTrue(result.contains("Error"));
    }

    @Test
    void toolReadFile_notFound_fails() {
        String result = S02ToolUse.toolReadFile(Map.of("file_path", "nonexistent_xyz.txt"));
        assertTrue(result.contains("Error"));
    }

    // ================================================================
    // processToolCall dispatch
    // ================================================================

    @Test
    void processToolCall_unknownTool_returnsError() {
        String result = S02ToolUse.processToolCall("unknown_tool", Map.of());
        assertTrue(result.contains("Unknown tool"));
    }
}
