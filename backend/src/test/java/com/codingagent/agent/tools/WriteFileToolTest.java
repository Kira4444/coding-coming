package com.codingagent.agent.tools;

import com.codingagent.agent.AgentLimits;
import com.codingagent.agent.ToolContext;
import com.codingagent.agent.ToolResult;
import com.codingagent.sandbox.SandboxSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * search/replace 是 agent 改代码的主路径，可靠性直接决定产品能不能用。
 * 这些用例覆盖「唯一匹配」「多处匹配拒绝」「找不到拒绝」三条关键分支。
 */
class WriteFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentLimits LIMITS = new AgentLimits(40, 500_000, 200, 20_000, 500, 100);

    @TempDir
    Path tempDir;

    private WriteFileTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new WriteFileTool();
        SandboxSession sandbox = new FakeSandbox(tempDir);
        ctx = new ToolContext(sandbox, LIMITS);
    }

    private ObjectNode edit(String oldText, String newText) {
        ObjectNode e = MAPPER.createObjectNode();
        if (oldText != null) {
            e.put("old_text", oldText);
        }
        e.put("new_text", newText);
        return e;
    }

    private ObjectNode request(String path, ObjectNode... edits) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("path", path);
        if (edits.length > 0) {
            ArrayNode arr = r.putArray("edits");
            for (ObjectNode e : edits) {
                arr.add(e);
            }
        }
        return r;
    }

    @Test
    void 唯一匹配时精确替换() throws IOException {
        Files.writeString(tempDir.resolve("a.java"), """
                class A {
                    int x = 1;
                }
                """);

        ToolResult r = tool.execute(ctx, request("a.java", edit("int x = 1;", "int x = 2;")));

        assertTrue(r.ok(), () -> "替换应成功，实际: " + r.content());
        String after = Files.readString(tempDir.resolve("a.java"));
        assertTrue(after.contains("int x = 2;"));
        assertFalse(after.contains("int x = 1;"));
    }

    @Test
    void 多处匹配时拒绝写入且不动文件() throws IOException {
        String original = """
                int x = 1;
                int y = 1;
                """;
        Files.writeString(tempDir.resolve("dup.txt"), original);

        ToolResult r = tool.execute(ctx, request("dup.txt", edit("int", "long")));

        assertFalse(r.ok(), "多处匹配必须拒绝");
        assertTrue(r.content().contains("匹配到 2 处"), () -> "报错要说清匹配数: " + r.content());
        assertEquals(original, Files.readString(tempDir.resolve("dup.txt")),
                "失败时文件必须保持原样，部分写入会让状态无法预测");
    }

    @Test
    void 找不到匹配时拒绝写入() throws IOException {
        String original = "hello\n";
        Files.writeString(tempDir.resolve("miss.txt"), original);

        ToolResult r = tool.execute(ctx, request("miss.txt", edit("nonexistent", "x")));

        assertFalse(r.ok());
        assertEquals(original, Files.readString(tempDir.resolve("miss.txt")));
    }

    @Test
    void 多处编辑全部成功时一起落盘() throws IOException {
        Files.writeString(tempDir.resolve("multi.txt"), "alpha\nbeta\ngamma\n");

        ToolResult r = tool.execute(ctx, request("multi.txt",
                edit("alpha", "ALPHA"),
                edit("gamma", "GAMMA")));

        assertTrue(r.ok(), () -> r.content());
        String after = Files.readString(tempDir.resolve("multi.txt"));
        assertEquals("ALPHA\nbeta\nGAMMA\n", after);
    }

    @Test
    void 多处编辑有一处失败则整体不写() throws IOException {
        String original = "alpha\nbeta\n";
        Files.writeString(tempDir.resolve("partial.txt"), original);

        ToolResult r = tool.execute(ctx, request("partial.txt",
                edit("alpha", "ALPHA"),
                edit("nonexistent", "x")));

        assertFalse(r.ok());
        assertEquals(original, Files.readString(tempDir.resolve("partial.txt")),
                "部分成功不允许写盘——模型预期的是「全成或全不成」");
    }

    @Test
    void content字段走整文件写入路径() throws IOException {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("path", "new/file.txt");
        r.put("content", "brand new");

        ToolResult result = tool.execute(ctx, r);

        assertTrue(result.ok(), () -> result.content());
        assertEquals("brand new", Files.readString(tempDir.resolve("new/file.txt")));
    }

    @Test
    void 相对路径越界返回失败而不是抛异常() throws IOException {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("path", "../outside.txt");
        r.put("content", "x");

        ToolResult result = tool.execute(ctx, r);

        assertFalse(result.ok(), "越界必须被拒");
        assertTrue(result.content().contains("越界"), () -> result.content());
    }

    /** 最小沙箱桩：只提供工作区路径 */
    private record FakeSandbox(Path root) implements SandboxSession {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public String workspacePath() {
            return root.toString();
        }

        @Override
        public ExecResult exec(String command, int timeoutSeconds) {
            throw new UnsupportedOperationException("本测试不执行命令");
        }

        @Override
        public Map<String, String> describe() {
            return Map.of("driver", "test");
        }

        @Override
        public Instant startedAt() {
            return Instant.now();
        }

        @Override
        public void close() {
        }
    }
}
