package com.codingagent.web;

import com.codingagent.auth.CurrentUser;
import com.codingagent.config.SecretCipher;
import com.codingagent.git.GitCredentials;
import com.codingagent.git.GitInfoService;
import com.codingagent.git.GitPublishService;
import com.codingagent.storage.entity.Project;
import com.codingagent.storage.entity.Session;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 发布配置与发布动作。
 *
 * <p>凭据只进不出：保存时加密入库，读取时只回掩码。要换凭据就重新填，
 * 不提供「查看原值」——那会让加密形同虚设。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/publish")
public class PublishController {

    private final SessionService sessionService;
    private final GitPublishService publishService;
    private final GitInfoService gitInfoService;
    private final SecretCipher secretCipher;
    private final CurrentUser currentUser;

    public PublishController(SessionService sessionService,
                             GitPublishService publishService,
                             GitInfoService gitInfoService,
                             SecretCipher secretCipher,
                             CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.publishService = publishService;
        this.gitInfoService = gitInfoService;
        this.secretCipher = secretCipher;
        this.currentUser = currentUser;
    }

    // ========== DTO ==========

    public record PublishConfigView(
            String publishBranch,
            String publishRemote,
            String credentialType,
            String username,
            /** 已配置凭据的掩码；未配置时为空串 */
            String credentialMasked,
            boolean credentialConfigured
    ) {
    }

    /** 保存配置。{@code credential} 为 null 表示不改；空串表示清除。 */
    public record SaveConfigRequest(
            String publishBranch,
            String publishRemote,
            String credentialType,
            String username,
            String credential
    ) {
    }

    public record PublishRequest(@NotBlank String sessionId) {
    }

    public record PublishResultView(boolean ok, String message, String details) {
    }

    public record GitInfoView(
            boolean gitRepo,
            String currentBranch,
            String remoteUrl,
            List<String> remotes,
            List<GitInfoService.CommitInfo> recentCommits,
            int commitsAhead,
            String message
    ) {
    }

    // ========== 配置 ==========

    @GetMapping("/config")
    public Mono<PublishConfigView> config(ServerHttpRequest request, @PathVariable Long projectId) {
        Project p = requireProject(request, projectId);
        return Mono.just(toConfigView(p));
    }

    @PutMapping("/config")
    public Mono<PublishConfigView> saveConfig(ServerHttpRequest request,
                                              @PathVariable Long projectId,
                                              @RequestBody SaveConfigRequest req) {
        Project p = requireProject(request, projectId);

        if (req.publishBranch() != null) {
            String b = req.publishBranch().trim();
            // 分支名要做合法性校验，否则能拼出带空格/分号的 refspec
            if (!b.isEmpty() && !b.matches("[A-Za-z0-9._/\\-]+")) {
                throw new IllegalArgumentException("发布分支名含非法字符");
            }
            p.setPublishBranch(b.isEmpty() ? null : b);
        }
        if (req.publishRemote() != null) {
            String r = req.publishRemote().trim();
            if (!r.isEmpty() && !r.matches("[A-Za-z0-9._\\-]+")) {
                throw new IllegalArgumentException("远程名含非法字符");
            }
            p.setPublishRemote(r.isEmpty() ? null : r);
        }
        if (req.credentialType() != null && !req.credentialType().isBlank()) {
            String t = req.credentialType().trim().toUpperCase();
            if (!"TOKEN".equals(t) && !"SSH_KEY".equals(t)) {
                throw new IllegalArgumentException("凭据类型只能是 TOKEN 或 SSH_KEY");
            }
            p.setPublishCredentialType(t);
        }
        if (req.username() != null) {
            p.setPublishUsername(req.username().trim().isEmpty() ? null : req.username().trim());
        }
        if (req.credential() != null) {
            // null = 不改；空串 = 清除；非空 = 加密后覆盖
            p.setPublishCredential(req.credential().isEmpty()
                    ? null
                    : secretCipher.encrypt(req.credential()));
        }

        sessionService.updateProjectPublishConfig(p);
        return Mono.just(toConfigView(p));
    }

    // ========== 发布 ==========

    @PostMapping
    public Mono<PublishResultView> publish(ServerHttpRequest request,
                                           @PathVariable Long projectId,
                                           @RequestBody PublishRequest req) {
        Project p = requireProject(request, projectId);
        Session s = sessionService.getSession(req.sessionId());
        if (s == null || !s.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("会话不存在或不属于该项目: " + req.sessionId());
        }
        if (p.getPublishBranch() == null || p.getPublishBranch().isBlank()) {
            throw new IllegalArgumentException("该项目尚未配置发布分支");
        }

        return Mono.fromCallable(() -> {
            try (GitCredentials creds = buildCredentials(p)) {
                GitPublishService.PublishResult r = publishService.publish(
                        s.getWorkspacePath(),
                        s.getBranchName(),
                        p.getPublishRemote(),
                        p.getPublishBranch(),
                        creds);
                sessionService.auditPublish(s, p, r.ok(), r.message());
                return new PublishResultView(r.ok(), r.message(), r.details());
            }
        });
    }

    // ========== Git 信息 ==========

    @GetMapping("/git-info")
    public Mono<GitInfoView> gitInfo(ServerHttpRequest request,
                                     @PathVariable Long projectId,
                                     @RequestParam String sessionId) {
        Project p = requireProject(request, projectId);
        Session s = sessionService.getSession(sessionId);
        if (s == null || !s.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("会话不存在或不属于该项目: " + sessionId);
        }
        return Mono.fromCallable(() -> {
            GitInfoService.GitInfo info = gitInfoService.info(s.getWorkspacePath(),
                    p.getPublishRemote(), 20);
            int ahead = (p.getPublishBranch() == null || p.getPublishBranch().isBlank())
                    ? -1
                    : gitInfoService.commitsAhead(s.getWorkspacePath(),
                    p.getPublishRemote(), p.getPublishBranch());
            return new GitInfoView(info.gitRepo(), info.currentBranch(), info.remoteUrl(),
                    info.remotes(), info.recentCommits(), ahead, info.message());
        });
    }

    // ========== 内部 ==========

    private Project requireProject(ServerHttpRequest request, Long projectId) {
        Long userId = currentUser.requireUserId(request);
        return sessionService.getProject(projectId, userId);
    }

    private GitCredentials buildCredentials(Project p) {
        String type = p.getPublishCredentialType();
        String secret = secretCipher.decrypt(p.getPublishCredential());
        if (secret == null) {
            // 没配凭据就走宿主机已有的 git 配置
            return GitCredentials.none();
        }
        return switch (type) {
            case "SSH_KEY" -> GitCredentials.sshKey(secret);
            case "TOKEN" -> GitCredentials.token(p.getPublishUsername(), secret);
            default -> GitCredentials.none();
        };
    }

    private PublishConfigView toConfigView(Project p) {
        String masked = "";
        boolean configured = false;
        if (p.getPublishCredential() != null && !p.getPublishCredential().isBlank()) {
            try {
                // 解密只为算掩码长度；明文不外传
                String plain = secretCipher.decrypt(p.getPublishCredential());
                masked = SecretCipher.mask(plain);
                configured = true;
            } catch (Exception e) {
                masked = "(密钥不匹配，无法解密)";
                configured = true;
            }
        }
        return new PublishConfigView(
                p.getPublishBranch(),
                p.getPublishRemote(),
                p.getPublishCredentialType(),
                p.getPublishUsername(),
                masked,
                configured);
    }
}
