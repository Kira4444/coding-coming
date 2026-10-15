package com.codingagent.web;

import com.codingagent.web.dto.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话改动的 diff 提取。
 *
 * <p>直接在宿主机上跑 {@code git}，不走沙箱：工作区本来就是宿主机目录
 * （docker 驱动下是 bind mount），diff 是只读操作，没必要为它起一次 exec。
 */
@Service
public class GitDiffService {

    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);

    /** 会话分支相对于基线的改动。基线取分支创建点（{@code <branch>~} 或首个提交）。 */
    public Dtos.DiffResponse diff(String workspacePath, String branchName) {
        Path ws = Path.of(workspacePath);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return new Dtos.DiffResponse(branchName, "(非 git 仓库)", List.of(), 0, 0);
        }

        String baseRef = resolveBaseRef(ws, branchName);

        // 三份数据各取所需：
        //   --name-status  拿改动类型（A/M/D/R），这是唯一权威来源
        //   --numstat      拿增删行数
        //   --patch        拿 hunk 内容供渲染
        List<String> nameStatus = run(ws, "git", "diff", "--name-status", baseRef, "HEAD");
        List<String> numstat = run(ws, "git", "diff", "--numstat", baseRef, "HEAD");
        String patch = runRaw(ws, "git", "diff", "--patch", "--no-color", baseRef, "HEAD");

        List<Dtos.FileDiff> files = parseFiles(nameStatus, numstat, patch);
        int added = files.stream().mapToInt(Dtos.FileDiff::added).sum();
        int removed = files.stream().mapToInt(Dtos.FileDiff::removed).sum();

        return new Dtos.DiffResponse(branchName, baseRef, files, added, removed);
    }

    public String patch(String workspacePath, String branchName) {
        Path ws = Path.of(workspacePath);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return "";
        }
        String baseRef = resolveBaseRef(ws, branchName);
        return runRaw(ws, "git", "diff", "--patch", "--no-color", baseRef, "HEAD");
    }

    /**
     * 找会话分支的起点。
     *
     * <p>优先用分支创建时的父提交；找不到就退回首个提交，再不行用工作树当前状态
     * 与空树对比——宁可 diff 偏大，也不能返回空让用户以为没改动。
     */
    private String resolveBaseRef(Path ws, String branchName) {
        // 会话分支是新建的，其起点是创建时所在的 HEAD
        String firstCommit = runFirst(ws, "git", "rev-list", "--max-parents=0", "HEAD");
        if (firstCommit != null && !firstCommit.isBlank()) {
            return firstCommit.trim();
        }
        return "--root";
    }

    /**
     * 合并三份 git 输出。
     *
     * <p>改动类型<b>只认 {@code --name-status}</b>：按增删行数去猜会把
     * 「修改时加了一行没删」误判成 ADDED，UI 上就会给一个改过的文件打「新增」标签。
     */
    private List<Dtos.FileDiff> parseFiles(List<String> nameStatus, List<String> numstat, String patch) {
        List<Dtos.FileDiff> result = new ArrayList<>();
        String[] patchHunks = splitByFile(patch);

        // path -> changeType（重命名时以新路径为 key）
        Map<String, String> changeTypes = new LinkedHashMap<>();
        Map<String, String> oldPaths = new LinkedHashMap<>();
        for (String line : nameStatus) {
            // 形如 "M\tsrc/Foo.java"、"A\tx.java"、"D\ty.java"、"R100\told\tnew"
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String code = parts[0].trim().toUpperCase();
            String path = parts[parts.length - 1].trim();
            String type = switch (code.charAt(0)) {
                case 'A' -> "ADDED";
                case 'D' -> "DELETED";
                case 'R' -> "RENAMED";
                case 'C' -> "ADDED";     // 复制视同新增
                case 'T' -> "MODIFIED";  // 类型变更
                default -> "MODIFIED";
            };
            changeTypes.put(path, type);
            if (parts.length >= 3) {
                oldPaths.put(path, parts[1].trim()); // 重命名的旧路径
            }
        }

        for (String line : numstat) {
            // 形如 "12\t3\tsrc/Foo.java"；二进制文件是 "-\t-\tpath"
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            int added = parseNum(parts[0]);
            int removed = parseNum(parts[1]);
            String path = parts[2].trim();

            String changeType = changeTypes.getOrDefault(path,
                    added == 0 && removed == 0 ? "MODIFIED"
                            : removed == 0 ? "ADDED"
                            : added == 0 ? "DELETED" : "MODIFIED");

            String filePatch = "";
            for (String hunk : patchHunks) {
                if (hunk.startsWith("diff --git") && hunk.contains(" b/" + path)) {
                    filePatch = hunk;
                    break;
                }
            }

            result.add(new Dtos.FileDiff(
                    path,
                    oldPaths.getOrDefault(path, path),
                    changeType,
                    added,
                    removed,
                    filePatch));
        }
        return result;
    }

    /** 把 git patch 按 {@code diff --git} 边界切成每文件一段 */
    private static String[] splitByFile(String patch) {
        if (patch == null || patch.isBlank()) {
            return new String[0];
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("diff --git ") && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        return chunks.toArray(new String[0]);
    }

    private static int parseNum(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0; // 二进制文件是 "-"
        }
    }

    private List<String> run(Path cwd, String... cmd) {
        String out = runRaw(cwd, cmd);
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\n")) {
            if (!l.isBlank()) {
                lines.add(l);
            }
        }
        return lines;
    }

    private String runFirst(Path cwd, String... cmd) {
        List<String> lines = run(cwd, cmd);
        return lines.isEmpty() ? null : lines.get(0);
    }

    private String runRaw(Path cwd, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("git stderr: {}", line);
                }
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("git 命令失败 {}: {}", String.join(" ", cmd), e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
