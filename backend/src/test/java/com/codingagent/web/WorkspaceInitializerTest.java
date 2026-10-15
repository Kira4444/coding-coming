package com.codingagent.web;

import com.codingagent.storage.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区初始化是「错了就全盘皆废」的地方——agent 会在一个空目录或错误目录里干活。
 */
class WorkspaceInitializerTest {

    private WorkspaceInitializer initializer;

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        initializer = new WorkspaceInitializer();
    }

    private Project project(String repoType, String url, String local) {
        Project p = new Project();
        p.setRepoType(repoType);
        p.setRepoUrl(url);
        p.setLocalPath(local);
        return p;
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
        }
        Files.writeString(f, content);
    }

    @Test
    void 本地git仓库clone出完整工作区() throws Exception {
        Path src = temp.resolve("src-repo");
        Files.createDirectories(src);
        write(src, "README.md", "# demo\n");
        write(src, "src/Main.java", "class Main {}\n");
        run(src, "git", "init", "-q");
        run(src, "git", "add", "-A");
        run(src, "git", "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-q", "-m", "init");

        Path ws = temp.resolve("ws");
        initializer.initialize(project("LOCAL_PATH", null, src.toString()), ws, "sess/branch1");

        assertTrue(Files.exists(ws.resolve("README.md")), "README 应被克隆过来");
        assertTrue(Files.exists(ws.resolve("src/Main.java")), "源码应被克隆过来");
        assertTrue(Files.isDirectory(ws.resolve(".git")), "应是 git 仓库");

        // 会话分支已建立
        String branch = runOut(ws, "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertEquals("sess/branch1", branch.trim());
    }

    @Test
    void 非git目录复制并补初始提交() throws Exception {
        Path src = temp.resolve("plain");
        Files.createDirectories(src);
        write(src, "app.js", "console.log(1)\n");
        write(src, "src/deep/nested.js", "ok\n");
        // 跳过清单：这些不该被复制
        write(src, "node_modules/x.js", "should-not-copy\n");
        write(src, "target/y.class", "should-not-copy\n");

        Path ws = temp.resolve("ws2");
        initializer.initialize(project("LOCAL_PATH", null, src.toString()), ws, "my-branch");

        assertTrue(Files.exists(ws.resolve("app.js")), "普通文件应被复制");
        assertTrue(Files.exists(ws.resolve("src/deep/nested.js")), "嵌套目录应被复制");
        assertFalse(Files.exists(ws.resolve("node_modules")), "node_modules 不应被复制");
        assertFalse(Files.exists(ws.resolve("target")), "target 不应被复制");

        assertTrue(Files.isDirectory(ws.resolve(".git")), "应补上 git 仓库");
        String branch = runOut(ws, "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertEquals("my-branch", branch.trim());

        // 有基线提交，diff 才有意义
        String log = runOut(ws, "git", "log", "--oneline");
        assertFalse(log.isBlank(), "应有初始提交");
    }

    @Test
    void 已有内容的工作区不被覆盖() throws Exception {
        Path src = temp.resolve("src2");
        Files.createDirectories(src);
        write(src, "a.txt", "from-src\n");
        run(src, "git", "init", "-q");
        run(src, "git", "add", "-A");
        run(src, "git", "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-q", "-m", "init");

        Path ws = temp.resolve("ws3");
        Files.createDirectories(ws);
        write(ws, "user-work.txt", "precious\n");

        // 模拟恢复会话：工作区已有内容
        initializer.initialize(project("LOCAL_PATH", null, src.toString()), ws, "b");

        assertTrue(Files.exists(ws.resolve("user-work.txt")), "用户改动不能被覆盖");
        assertFalse(Files.exists(ws.resolve("a.txt")), "不应重复初始化");
    }

    @Test
    void 来源路径不存在时报错() {
        Path ws = temp.resolve("ws4");
        var e = assertThrows(WorkspaceInitializer.InitException.class, () ->
                initializer.initialize(project("LOCAL_PATH", null, temp.resolve("nope").toString()),
                        ws, "b"));
        assertTrue(e.getMessage().contains("不存在"), () -> e.getMessage());
    }

    @Test
    void 工作区与来源相同时报错() throws Exception {
        Path src = temp.resolve("same");
        Files.createDirectories(src);
        var e = assertThrows(WorkspaceInitializer.InitException.class, () ->
                initializer.initialize(project("LOCAL_PATH", null, src.toString()), src, "b"));
        assertTrue(e.getMessage().contains("相同"), () -> e.getMessage());
    }

    @Test
    void 未配置来源时报错() {
        Path ws = temp.resolve("ws5");
        var e1 = assertThrows(WorkspaceInitializer.InitException.class, () ->
                initializer.initialize(project("GIT_URL", null, null), ws, "b"));
        assertTrue(e1.getMessage().contains("git 地址"), () -> e1.getMessage());

        var e2 = assertThrows(WorkspaceInitializer.InitException.class, () ->
                initializer.initialize(project("LOCAL_PATH", null, null), ws, "b"));
        assertTrue(e2.getMessage().contains("本地路径"), () -> e2.getMessage());
    }

    // ---------- 辅助 ----------

    private static void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("命令失败: " + String.join(" ", cmd));
        }
    }

    private static String runOut(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("命令失败: " + String.join(" ", cmd));
        }
        return out;
    }
}
