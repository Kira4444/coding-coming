package com.codingagent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把会话改动发布到同仓库的发布分支（支线仓库）。
 *
 * <p>语义：{@code git push <remote> <会话分支>:<发布分支>}。
 * 会话分支是主线的后继时是快进推送；两者分叉时 git 会拒绝——
 * 我们把拒绝原因原样告诉用户，而不是偷偷 force。
 */
@Service
public class GitPublishService {

    private static final Logger log = LoggerFactory.getLogger(GitPublishService.class);
    private static final int TIMEOUT = 300;

    private final GitCommandRunner runner;

    public GitPublishService(GitCommandRunner runner) {
        this.runner = runner;
    }

    public record PublishResult(boolean ok, String message, String details) {
    }

    /**
     * 发布。
     *
     * @param workspacePath 会话工作区
     * @param sessionBranch 会话分支
     * @param remote        远程名，默认 origin
     * @param publishBranch 目标发布分支
     * @param credentials   推送凭据
     */
    public PublishResult publish(String workspacePath, String sessionBranch,
                                 String remote, String publishBranch,
                                 GitCredentials credentials) {
        Path ws = Path.of(workspacePath);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return new PublishResult(false, "工作区不是 git 仓库，无法发布", "");
        }
        String r = (remote == null || remote.isBlank()) ? "origin" : remote.trim();
        String target = (publishBranch == null || publishBranch.isBlank()) ? "" : publishBranch.trim();

        try {
            // 1. 确认远程存在
            GitCommandRunner.GitResult remotes = runner.run(ws, credentials.env(), 60, "remote");
            if (!remotes.ok()) {
                return new PublishResult(false, "读取远程列表失败", remotes.combined());
            }
            if (!containsWord(remotes.stdout(), r)) {
                return new PublishResult(false,
                        "仓库没有名为 [" + r + "] 的远程。已配置: " + remotes.stdout().trim(),
                        remotes.combined());
            }

            // 2. 先 fetch，否则推之前对远端状态一无所知
            GitCommandRunner.GitResult fetch = runner.run(ws, credentials.env(), TIMEOUT,
                    "fetch", r, "--prune");
            if (!fetch.ok()) {
                return new PublishResult(false, "拉取远程失败，无法确认发布分支状态", fetch.combined());
            }

            // 3. 确认会话分支存在且有提交
            GitCommandRunner.GitResult head = runner.run(ws, credentials.env(), 30,
                    "rev-parse", "--verify", sessionBranch);
            if (!head.ok()) {
                return new PublishResult(false,
                        "会话分支 [" + sessionBranch + "] 不存在或没有提交", head.combined());
            }

            if (target.isEmpty()) {
                return new PublishResult(false, "未配置发布分支", "");
            }

            // 4. 推送：会话分支 → 发布分支
            String refspec = sessionBranch + ":" + target;
            GitCommandRunner.GitResult push = runner.run(ws, credentials.env(), TIMEOUT,
                    "push", "--porcelain", r, refspec);
            if (push.ok()) {
                String summary = summarizePush(push.stdout());
                log.info("发布成功 {} → {}:{} {}", workspacePath, r, target, summary);
                return new PublishResult(true,
                        "已发布到 " + r + "/" + target, summary + "\n" + push.stdout().trim());
            }

            // 5. 失败：区分「分叉」与其他原因
            String err = push.combined();
            if (err.contains("non-fast-forward") || err.contains("fetch first")
                    || err.contains("rejected")) {
                return new PublishResult(false,
                        "发布被拒绝：会话分支与 " + r + "/" + target + " 已分叉。" +
                                "请先在本地合并，或确认要覆盖后再操作——本产品不会自动 force 覆盖。",
                        err);
            }
            return new PublishResult(false, "推送失败", err);

        } catch (Exception e) {
            log.error("发布异常", e);
            return new PublishResult(false, "发布异常: " + e.getMessage(), "");
        }
    }

    /** 从 {@code --porcelain} 输出里提取一行摘要 */
    private static String summarizePush(String porcelain) {
        if (porcelain == null) {
            return "";
        }
        for (String line : porcelain.split("\n")) {
            String t = line.trim();
            if (t.startsWith("To ") || t.startsWith("= ") || t.startsWith("* ")) {
                return t;
            }
        }
        return "";
    }

    private static boolean containsWord(String haystack, String word) {
        if (haystack == null) {
            return false;
        }
        for (String line : haystack.split("\n")) {
            if (line.trim().equals(word)) {
                return true;
            }
        }
        return false;
    }
}
