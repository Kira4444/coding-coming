package com.codingagent.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * git 推送凭据注入。
 *
 * <p>两条铁律：
 * <ul>
 *   <li><b>凭据不进命令行</b>——{@code ps} 能看到进程参数</li>
 *   <li><b>凭据不进 git config</b>——会持久化到仓库，clone 出去就泄了</li>
 * </ul>
 *
 * <p>因此：
 * <ul>
 *   <li>HTTPS token 走 {@code GIT_ASKPASS} + 环境变量，脚本本身不含密钥</li>
 *   <li>SSH key 写临时文件（0600），用 {@code GIT_SSH_COMMAND -i} 指定，用完删</li>
 * </ul>
 */
public final class GitCredentials implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GitCredentials.class);

    /** askpass 脚本。密钥从环境变量读，脚本本身是安全的静态内容。 */
    private static final String ASKPASS_SH = """
            #!/bin/sh
            case "$1" in
              *[Uu]sername*) printf '%s' "${CA_USERNAME}" ;;
              *)             printf '%s' "${CA_TOKEN}" ;;
            esac
            """;

    private static final String ASKPASS_CMD = """
            @echo off
            setlocal
            echo %CA_TOKEN%
            """;

    private final Map<String, String> env = new LinkedHashMap<>();
    private Path tempKeyFile;

    private GitCredentials() {
    }

    /** 无凭据：走宿主机已有的 git 配置 */
    public static GitCredentials none() {
        return new GitCredentials();
    }

    /**
     * HTTPS token。
     *
     * @param username 常为 {@code git} 或 {@code oauth2}；为 null 时让 git 自己决定
     */
    public static GitCredentials token(String username, String token) {
        GitCredentials c = new GitCredentials();
        if (token == null || token.isBlank()) {
            return c;
        }
        try {
            boolean isWindows = System.getProperty("os.name", "")
                    .toLowerCase().contains("win");
            String script = isWindows ? ASKPASS_CMD : ASKPASS_SH;
            String suffix = isWindows ? ".cmd" : ".sh";

            Path dir = Files.createTempDirectory("coding-agent-askpass");
            Path file = dir.resolve("askpass" + suffix);
            Files.writeString(file, script, StandardCharsets.UTF_8);
            if (!isWindows) {
                try {
                    Files.setPosixFilePermissions(file,
                            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE));
                } catch (UnsupportedOperationException ignored) {
                    // Windows 文件系统不支持 POSIX 权限
                }
            }
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();

            c.env.put("GIT_ASKPASS", file.toString());
            c.env.put("CA_TOKEN", token);
            if (username != null && !username.isBlank()) {
                c.env.put("CA_USERNAME", username);
                // username 不是机密，走 -c 配置可以省掉一次 askpass 往返
                c.env.put("GIT_CONFIG_COUNT", "1");
                c.env.put("GIT_CONFIG_KEY_0", "credential.username");
                c.env.put("GIT_CONFIG_VALUE_0", username);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 askpass 脚本失败: " + e.getMessage(), e);
        }
        return c;
    }

    /** SSH 私钥 */
    public static GitCredentials sshKey(String privateKey) {
        GitCredentials c = new GitCredentials();
        if (privateKey == null || privateKey.isBlank()) {
            return c;
        }
        try {
            Path dir = Files.createTempDirectory("coding-agent-ssh");
            Path key = dir.resolve("id_deploy");
            // SSH 会拒绝权限过宽的私钥，必须收紧
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Files.writeString(key, privateKey, StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } else {
                Files.writeString(key, privateKey, StandardCharsets.UTF_8);
            }
            c.tempKeyFile = key;
            dir.toFile().deleteOnExit();
            key.toFile().deleteOnExit();

            // accept-new：首次连接自动收下 host key，避免交互式确认卡死
            c.env.put("GIT_SSH_COMMAND",
                    "ssh -i " + key + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new");
        } catch (IOException e) {
            throw new IllegalStateException("写入 SSH 私钥失败: " + e.getMessage(), e);
        }
        return c;
    }

    public Map<String, String> env() {
        return new HashMap<>(env);
    }

    @Override
    public void close() {
        if (tempKeyFile != null) {
            try {
                Files.deleteIfExists(tempKeyFile);
                if (tempKeyFile.getParent() != null) {
                    Files.deleteIfExists(tempKeyFile.getParent());
                }
            } catch (IOException e) {
                log.warn("清理临时私钥失败: {}", e.getMessage());
            }
            tempKeyFile = null;
        }
    }
}
