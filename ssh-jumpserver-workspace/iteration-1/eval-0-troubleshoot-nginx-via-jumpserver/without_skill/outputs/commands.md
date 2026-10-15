# 命令与工具调用清单

按执行/计划顺序记录。已真实执行的标注 **[已执行]**，因网络不通未执行的标注 **[计划]**。

密码 `Test#2024` 仅用于 SSH 认证参数，**不**出现在 shell 命令字符串里（避免 `ps` 泄漏）。

---

## 0. 准备

| # | 动作 | 说明 |
|---|------|------|
| 1 | `mkdir -p .../without_skill/outputs` **[已执行]** | 创建本次输出目录 |

---

## 1. 连接堡垒机（一次尝试）

| # | 动作 | 说明 |
|---|------|------|
| 2 | MCP `ssh_connect`：`host=jms.company.com`, `port=2222`, `username=admin`, `password=Test#2024`, `name=jumpserver-jms` **[已执行]** | 用堡垒机入口做一次连接尝试，验证连通性与认证方式 |

结果：

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

DNS 解析失败，未建立会话。未再重试、未改 hosts、未猜 IP。

等价 CLI 写法（网络可用时，密码走交互或 askpass，不进 argv）：

```bash
ssh -p 2222 -o PreferredAuthentications=password -o PubkeyAuthentication=no admin@jms.company.com
# 交互输入密码；或使用 SSH_ASKPASS / 受控凭据注入
```

| # | 动作 | 说明 |
|---|------|------|
| 3 | MCP `ssh_list_connections` **[未执行]** | 若 connect 成功则确认 connectionId；本次失败，无需 |

---

## 2. [计划] 通过堡垒机进入 web-01

JumpServer（端口 2222）通常是 SSH 网关：登录后出现资产列表，选 `web-01`。

| # | 动作 | 说明 |
|---|------|------|
| 4 | `ssh -p 2222 admin@jms.company.com` 然后在菜单选 `web-01` / `10.1.2.3` **[计划]** | 标准 JumpServer 资产连接方式 |
| 5 | 若支持网关直连：`ssh -o ProxyCommand='ssh -W %h:%p -p 2222 admin@jms.company.com' root@10.1.2.3` **[计划，仅当环境支持]** | ProxyJump 风格直达目标，视堡垒机配置而定 |
| 6 | 确认当前身份：`whoami; hostname` **[计划]** | 确认是否已在 `web-01` 且为 root |

若已通过 MCP 连上堡垒机会话，后续远程命令用 `ssh_exec(connectionId=..., command=...)`。

---

## 3. [计划] 只读排查 nginx（在 web-01 上）

按顺序，每条一行原因：

| # | 命令 | 为什么 |
|---|------|--------|
| 7 | `systemctl status nginx --no-pager -l` | 看服务是 dead / failed / active，以及最近状态行 |
| 8 | `nginx -t` | 配置语法是否合法；很多“起不来”卡在这里 |
| 9 | `ss -lntp \| grep -E ':(80\|443)\b'` | 端口是否被别的进程占用 |
| 10 | `ls -la /var/log/nginx/` | 确认日志文件名、大小、属主（你说的目录） |
| 11 | `tail -n 100 /var/log/nginx/error.log` | 错误日志是定位 emrg/error 的第一现场 |
| 12 | `tail -n 100 /var/log/nginx/access.log` | 对照访问日志，看是否曾有请求、是否 5xx |
| 13 | `journalctl -u nginx -n 50 --no-pager` | systemd 启动失败原因（权限、依赖、被 kill） |
| 14 | `df -h` | 磁盘满会导致无法写日志、无法启动 |
| 15 | `ls -la /etc/nginx/` | 配置目录与权限 |
| 16 | `find /etc/nginx -name '*.conf' -mtime -7` | 最近是否有人改过配置（时间线） |
| 17 | （视情况）`grep -RniE 'ssl_certificate|root |proxy_pass' /etc/nginx/` | 核对证书路径、站点 root、upstream 是否指向错误 |

---

## 4. [计划] 确认原因后的修复（需用户确认后才做）

| # | 动作 | 说明 |
|---|------|------|
| 18 | 向用户说明根因 + 修复步骤 + 回滚方式 **[计划]** | 变更前必过用户 |
| 19 | （若改配置）`cp -a /etc/nginx /etc/nginx.bak.$(date +%F-%H%M%S)` **[计划]** | 备份后再动 |
| 20 | （若改配置）`nginx -t` **[计划]** | 语法通过才继续 |
| 21 | `systemctl restart nginx` 或 `nginx -s reload` **[计划]** | 视是全量启动失败还是仅 reload |
| 22 | `systemctl status nginx` + `curl -I http://127.0.0.1/` **[计划]** | 验证恢复 |

**不会**在未看清日志/`nginx -t` 前盲 `restart`/`kill -9`。

---

## 5. 断开

| # | 动作 | 说明 |
|---|------|------|
| 23 | MCP `ssh_disconnect` **[计划]** | 排查结束后关闭会话；本次因连接失败未建立 |

---

## 备注

- 真实执行仅第 1–2 条；其余为网络打通后的完整计划命令序列。
- 所有远程命令尽量只读，写操作单独列出并等待确认。
