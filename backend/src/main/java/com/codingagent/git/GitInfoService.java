package com.codingagent.git;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 会话工作区的 git 状态，供界面展示。 */
@Service
public class GitInfoService {

    private final GitCommandRunner runner;

    public GitInfoService(GitCommandRunner runner) {
        this.runner = runner;
    }

    public record CommitInfo(String hash, String shortHash, String subject, String author, String date) {
    }

    public record GitInfo(
            boolean gitRepo, String currentBranch, String remoteUrl,
            List<String> remotes, List<CommitInfo> recentCommits, String message) {
    }

    public GitInfo info(String workspacePath, String remote, int limit) {
        Path ws = Path.of(workspacePath);
        if (!Files.isDirectory(ws)) {
            return new GitInfo(false, null, null, List.of(), List.of(), "工作区不存在");
        }
        if (!Files.isDirectory(ws.resolve(".git"))) {
            return new GitInfo(false, null, null, List.of(), List.of(), "工作区不是 git 仓库");
        }
        GitCommandRunner.GitResult branch = runner.run(ws, null, 30,
                "rev-parse", "--abbrev-ref", "HEAD");
        String currentBranch = branch.ok() ? branch.stdout().trim() : null;

        List<String> remoteList = new ArrayList<>();
        GitCommandRunner.GitResult remotes = runner.run(ws, null, 30, "remote");
        if (remotes.ok()) {
            for (String line : remotes.stdout().split("\n")) {
                if (!line.isBlank()) remoteList.add(line.trim());
            }
        }
        String r = (remote == null || remote.isBlank()) ? "origin" : remote.trim();
        String remoteUrl = null;
        if (remoteList.contains(r)) {
            GitCommandRunner.GitResult url = runner.run(ws, null, 30, "remote", "get-url", r);
            if (url.ok()) remoteUrl = url.stdout().trim();
        }

        int n = Math.max(1, Math.min(limit, 100));
        GitCommandRunner.GitResult log = runner.run(ws, null, 30,
                "log", "--no-color", "-n", String.valueOf(n),
                "--date=short", "--pretty=format:%H%x1f%h%x1f%s%x1f%an%x1f%cd");

        List<CommitInfo> commits = new ArrayList<>();
        if (log.ok()) {
            for (String line : log.stdout().split("\n", -1)) {
                String[] parts = line.split("\u001f", -1);
                if (parts.length >= 5) {
                    commits.add(new CommitInfo(parts[0], parts[1], parts[2], parts[3], parts[4]));
                }
            }
        }
        return new GitInfo(true, currentBranch, remoteUrl, remoteList, commits,
                log.ok() ? null : log.combined());
    }

    public int commitsAhead(String workspacePath, String remote, String publishBranch) {
        Path ws = Path.of(workspacePath);
        String r = (remote == null || remote.isBlank()) ? "origin" : remote.trim();
        String ref = r + "/" + publishBranch;
        GitCommandRunner.GitResult revList = runner.run(ws, null, 30,
                "rev-list", "--count", ref + "..HEAD");
        if (!revList.ok()) return -1;
        try {
            return Integer.parseInt(revList.stdout().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
