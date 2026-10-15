# 备注与安全决策

## 用户提供的连接信息（已收齐，无需再问）

| 项 | 值 |
|---|---|
| 堡垒机 | jms.company.com:2222 |
| JumpServer 登录账号 | admin |
| 认证方式 | 密码（用户口头提供） |
| 目标资产 | web-01 / 10.1.2.3 |
| 目标机登录 | root |
| 日志线索 | /var/log/nginx/ |

四样（地址、用户名、资产标识、认证方式）齐全，按 skill 可以直接连，不需要反问资产。

## 密码处理（关键决策）

- **不进命令行**：未使用 `sshpass -p`、未 `ssh -o` 塞密码、未 `echo 密码 | ssh`。
- **不进文件**：未写入 ssh config、askpass 脚本、connections.json、任何输出文件（本目录三个 md 均无密码）。
- **不回显**：回复正文与本笔记均不复述密码，也不做“确认一下密码是不是……”。
- **选用路径**：skill 优先级 2 —— SSH MCP `ssh_connect(password=...)`。密码作为工具参数传递，不进进程 argv。
- **未降级到 SSH_ASKPASS**：那条路要求把密码放进 shell 的 `export SSH_PASSWORD=...`，等于进了命令行字符串；在 MCP 被拒后拒绝这样做，改为只做无凭据连通性探测并停住等用户。
- 用完即弃：不进 memory、不进 connections.json。若用户以后要免密，走 JumpServer 个人资料加 SSH 公钥。

## 本次实际连接结果

1. SSH MCP 直连语法 `username=root#10.1.2.3#admin` → 权限分类器拒绝（先报 transient，重试仍拒绝）。这不是网络或凭据问题，是本环境未放行 `mcp__ssh__ssh_connect`。
2. 无凭据 CLI 探测同一语法 → `Could not resolve hostname jms.company.com`（exit 255）。模拟环境中内网域名不解析，符合预期。
3. **未登上 web-01，nginx 未开始排查。** 不臆造根因（不猜是磁盘满还是配置错），等真实日志。

## 语法选择

- 用 `root#10.1.2.3#admin`（变体 A：系统用户#资产IP#登录用户），符合「用 root 登 10.1.2.3」。
- 备选：`root#web-01#admin`（变体 C，资产名）。两种都是 skill 探测顺序里的。
- 分隔符是 `#`，不是 `@`；`@` 只分隔用户名和堡垒机地址。

## 安全与操作边界

- 本次只有只读意图（看日志/状态/磁盘/端口）。
- 重启 nginx、改配置、清磁盘属于写操作：即使测试环境也会先贴命令原文等用户确认；跨环境（若误连到生产）重新授权。
- JumpServer 会话被审计录屏——不绕过、不关日志。
- 动手前必核 `hostname` + IP，与 web-01 / 10.1.2.3 对齐。
- 日志用 `tail -n 200`，不 `cat` 大文件（工具输出截断是承重墙）。

## 若继续，需要用户提供

- 我这边可解析的堡垒机入口（公网域名/IP），或允许出网/加 DNS；**或者**
- 在可连内网的机器上执行 commands.md 里第 4–12 条并回传输出。

## 配置固化（用户未要求，仅备用）

若用户要「以后一条命令连上」，产出 ssh config，形如：

```
Host web-01
  HostName jms.company.com
  Port 2222
  User root#10.1.2.3#admin
  IdentityFile ~/.ssh/id_ed25519
  ServerAliveInterval 30
```

其中 `#` 写在 `User` 值中间是安全的（仅行首是注释）。不写 Password 字段；密码或公钥免密单独说明。
