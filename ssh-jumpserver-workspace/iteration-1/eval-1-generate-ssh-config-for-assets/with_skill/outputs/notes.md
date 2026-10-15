# notes —— 密码处理与你还需要做的事

## 密码处理（红线，已遵守）

- **配置文件里没有任何密码**。ssh config 协议本身不支持 Password 字段，写不进去——这是设计如此。目标机 root 密码由 JumpServer 托管，本地只需 JumpServer 账号的认证，这两层密码都不会、也不应出现在 `ssh_config` 里。
- **回复文本里也没有密码**。用户没有提供密码，本任务也不需要。
- **没有把密码写进命令行**（`sshpass -p` / `ssh -o` 塞密码之类都没用）。没有调用任何 SSH 连接工具。
- 后续如果要做需要真实连接的操作：密码只走 SSH MCP 的 `ssh_connect(password=...)` 或 `SSH_ASKPASS`（环境变量），用完即弃；不进 argv、不进文件、不进 memory。

## 语法选择说明

- 采用 JumpServer koko 直连语法 **A：`系统用户#资产IP#登录用户`**（`root#10.1.2.3#admin`），JumpServer 2.10+ / 3.x / 4.x 通用，不依赖资产命名。
- 备选 **C：资产名形式**（`root#web-01#admin`）写在 `ssh_config` 末尾注释里，IP 形式失败时替换 `User` 即可。
- `User` 值里的 `#` 在 ssh config 中**不是**注释（注释只认行首 `#`），可以安全使用。
- ProxyJump 备选也放在注释里：多数合规 JumpServer 部署不允许端口转发到资产 22 端口，所以不是主路径。

## 你还需要做的事

1. **合并配置**：把 `ssh_config` 内容放进 `~/.ssh/config`（Windows：`C:\Users\<你>\.ssh\config`），`chmod 600`。已有内容则追加，不要覆盖。
2. **确认 IdentityFile 路径**：当前写的是 `~/.ssh/id_ed25519`。若你用 `id_rsa` 或别的路径，改这行。
3. **配 JumpServer 免密（强烈推荐）**：
   - 本机没有密钥就 `ssh-keygen -t ed25519`
   - 把 `~/.ssh/id_ed25519.pub` 贴到 JumpServer Web → 个人设置 → SSH 公钥
   - 之后 `ssh web-01` 全程免密。资产机的 `authorized_keys` 不用动——那是 JumpServer 系统用户的事。
4. **不配免密的话**：每次连接在密码提示里输入 JumpServer 密码（若开 MFA 还要验证码）。没有第三种安全选项，不要试图把密码塞进配置或脚本。
5. **首次连接核实资产**：`hostname` + `ip addr` 与 10.1.2.3 / 10.1.2.4 / 10.1.2.5 对上再操作。JumpServer 会话被审计录屏，进错机器很麻烦。
6. **确认资产授权**：若 `Permission denied`，去 JumpServer Web 检查 admin 账号对 web-01 / web-02 / redis-test 三台资产、以及 root 系统用户的授权。
7. **确认端口**：配置按默认 2222 写。若你们 koko 端口改过（也有部署用 22），把所有 `Port 2222` 改掉。

## 潜在坑

- 一台 JumpServer 若是私有化/改造版，直连用户名语法可能以 Web 界面「复制 SSH 命令」按钮给出的字符串为准——那个是权威。若和本配置的 `User` 字段不一致，按界面复制的来。
- koko 对 SFTP 支持有限；需要传文件时优先试 `scp`/`sftp`，不行再走 ProxyJump 或临时 HTTP。
- 三台共用同一份 `Host web-01 web-02 redis-test` 公共块，改端口/密钥时改一处即可。
