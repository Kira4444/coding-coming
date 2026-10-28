package com.codingagent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话工作区的代码快照（rewind 的地基）。
 *
 * <p>两类 git 对象，都<b>不碰工作区真实索引</b>：
 * <ul>
 *   <li>轮次快照：每轮结束时把「工作区树」存成一个快照 commit，滚动挂在
 *       {@code refs/coding-agent/snap/<sessionId>} 下；</li>
 *   <li>操作备份：每次回滚/恢复前再快照一次当前状态，独立挂在
 *       {@code refs/coding-agent/backup/<sessionId>/<opId>} 下，永不覆盖。</li>
 * </ul>
 *
 * <p><b>为什么存 commit 而不是裸 tree</b>：没有 commit 引用的 tree 会被
 * {@code git gc} 清掉。快照 commit 的父提交 = 当时的 HEAD（保住 agent 用
 * git_commit 打的普通提交）+ 上一快照（把整条快照链挂在 ref 下），
 * 于是被回滚掉的提交永远从备份 ref 可达，历史一条不丢。
 *
 * <p>在宿主机上跑 git，与沙箱驱动（local / docker）无关——工作区对两个驱动
 * 都是宿主机上的同一个目录（docker 是 bind mount），快照/恢复必须看到同一份文件。
 */
@Service
public class GitSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GitSnapshotService.class);

    private static final int TIMEOUT_SECONDS = 120;

    private final GitCommandRunner gitRunner;

    public GitSnapshotService(GitCommandRunner gitRunner) {
        this.gitRunner = gitRunner;
    }

    /** 快照链滚动 ref：refs/coding-agent/snap/<sessionId> */
    public static String snapRef(String sessionId) {
        return "refs/coding-agent/snap/" + sessionId;
    }

    /** 操作备份 ref：refs/coding-agent/backup/<sessionId>/<opId>，永不覆盖 */
    public static String backupRef(String sessionId, long opId) {
        return "refs/coding-agent/backup/" + sessionId + "/" + opId;
    }

    /** 一次快照的结果：当时的 HEAD 与快照 commit sha */
    public record Snapshot(String headSha, String snapshotSha) {
    }

    /**
     * 给工作区当前状态（含未提交改动）打快照，并把 {@code refName} 指到新快照上。
     *
     * <p>用临时索引（{@code GIT_INDEX_FILE}）以 HEAD 为基做 {@code add -A}——
     * 这样删除也能被记录下来，而工作区真实的 index 毫发无损。
     *
     * @param message 提交说明，如 {@code coding-agent snapshot turn 5}
     */
    public Snapshot snapshot(Path workspace, String sessionId, String refName, String message) {
        Path idx = tempIndexFile();
        try {
            Map<String, String> env = new HashMap<>();
            env.put("GIT_INDEX_FILE", idx.toString());

            // 临时索引以 HEAD 为基，让 add -A 能把「删除」也记进快照
            require(run(workspace, env, "read-tree", "HEAD"), "read-tree HEAD");

            // .gitignore 命中的不进快照——与 git 语义一致；
            // 回滚后残留的 untracked 垃圾（如 node_modules）不清理，属预期
            require(run(workspace, env, "add", "-A"), "add -A");

            String treeSha = require(run(workspace, env, "write-tree"), "write-tree").stdout().trim();
            String headSha = require(run(workspace, env, "rev-parse", "HEAD"), "rev-parse HEAD")
                    .stdout().trim();

            // 父提交 = 当时 HEAD + 上一快照：两个父各保一条命（历史 & 快照链）
            String prev = revParse(workspace, snapRef(sessionId));
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("commit-tree");
            cmd.add(treeSha);
            cmd.add("-p");
            cmd.add(headSha);
            if (prev != null && !prev.isBlank()) {
                cmd.add("-p");
                cmd.add(prev.trim());
            }
            cmd.add("-m");
            cmd.add(message);
            GitCommandRunner.GitResult r = run(workspace, Map.of(), cmd.toArray(new String[0]));
            String snapSha = require(r, "commit-tree").stdout().trim();

            require(run(workspace, Map.of(), "update-ref", refName, snapSha), "update-ref " + refName);
            log.debug("[{}] 快照 {} head={} snap={}", sessionId, refName, headSha, snapSha);
            return new Snapshot(headSha, snapSha);
        } finally {
            deleteQuietly(idx);
        }
    }

    /**
     * 把工作区切回某个快照记录的状态。
     *
     * <p>两步缺一不可：
     * <ol>
     *   <li>{@code reset --hard <headSha>}——分支尖端回到该轮结束时的 HEAD，
     *       保留 agent 打的提交历史；</li>
     *   <li>{@code read-tree --reset -u <tree>}——工作区+索引精确回到快照的树
     *       （含未提交改动）。</li>
     * </ol>
     *
     * <p>⚠ 不能直接 {@code reset --hard <snapshotSha>}：快照 commit 在独立父链上，
     * 那样会把会话分支指到快照链，发布（push 会话分支）、git log、diff 全乱。
     */
    public void restore(Path workspace, String headSha, String snapshotSha) {
        require(run(workspace, Map.of(), "reset", "--hard", headSha), "reset --hard " + abbrev(headSha));
        String tree = require(run(workspace, Map.of(), "rev-parse", snapshotSha + "^{tree}"),
                "rev-parse " + abbrev(snapshotSha) + "^{tree}").stdout().trim();
        require(run(workspace, Map.of(), "read-tree", "--reset", "-u", tree),
                "read-tree --reset -u " + abbrev(tree));
    }

    /** 读 ref 指向的 sha；不存在或失败返回 null */
    public String revParse(Path workspace, String ref) {
        GitCommandRunner.GitResult r = run(workspace, Map.of(), "rev-parse", ref);
        return r.ok() ? r.stdout().trim() : null;
    }

    /** 把 ref 指到某个已存在的快照 commit（备份 ref 落位用）。 */
    public void updateRef(Path workspace, String refName, String sha) {
        require(run(workspace, Map.of(), "update-ref", refName, sha), "update-ref " + refName);
    }

    private GitCommandRunner.GitResult run(Path workspace, Map<String, String> env, String... args) {
        return gitRunner.run(workspace, env, TIMEOUT_SECONDS, args);
    }

    private static GitCommandRunner.GitResult require(GitCommandRunner.GitResult r, String what) {
        if (!r.ok()) {
            throw new IllegalStateException("git " + what + " 失败: " + trim(r.combined()));
        }
        return r;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String abbrev(String sha) {
        return sha == null ? "" : (sha.length() > 7 ? sha.substring(0, 7) : sha);
    }

    /** 临时索引文件：git 要求索引要么不存在、要么合法，所以先建后删成「不存在」 */
    private static Path tempIndexFile() {
        try {
            Path p = Files.createTempFile("coding-agent-idx-", ".tmp");
            Files.deleteIfExists(p);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("创建临时索引文件失败: " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件残留无害
        }
    }
}
