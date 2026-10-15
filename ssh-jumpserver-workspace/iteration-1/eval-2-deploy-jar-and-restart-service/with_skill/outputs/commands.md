# 命令与工具调用清单（按顺序）

密码**从未**进入命令行参数、配置文件或明文落盘。下列「已执行」项为本次实际调用；「计划」项为连接成功并经用户确认后才会执行的远程步骤。

---

## 0. 本地准备

| # | 类型 | 命令/调用 | 结果 |
|---|---|---|---|
| 0.1 | Bash | `mkdir -p .../with_skill/outputs`，确认 `myapp.jar` 路径 | 输出目录已建；本地按题意假定 `myapp.jar` 存在 |

---

## 1. 连接尝试（已执行，仅一次）

路径选择：有 SSH MCP → 优先 MCP；目标机 `10.2.3.4` 需过堡垒机 → JumpServer **直连语法**（用户名嵌资产标识，可自动化）。

| # | 类型 | 调用 | 说明 |
|---|---|---|---|
| 1.1 | MCP `ssh_connect` | `host=jms.company.com` `port=2222` `username=app#10.2.3.4#ops01` `password=<用户提供，仅工具参数>` `name=jms-prod-app-10.2.3.4` | JumpServer 直连语法变体 A：`系统用户#资产IP#登录用户` → `app#10.2.3.4#ops01` |

**结果**：`SSH connection failed: getaddrinfo ENOTFOUND jms.company.com`

**未做**（刻意）：
- 未把密码写入 `sshpass` / `ssh -o` / 任何 CLI argv
- 未写 `~/.ssh/config`、`connections.json`、askpass 脚本
- 未改试 ProxyJump / 交互菜单 / 其它语法变体（环境说明要求只探一次；且 DNS 都不通，换语法无意义）
- 未安装任何工具、未做任何“绕过网络”尝试

---

## 2. 连接成功后的只读检查（计划，无需用户再确认）

```
hostname
hostname -I 2>/dev/null || ip -4 addr show | awk '/inet /{print $2}'
uname -a
df -h /opt /tmp
systemctl status myapp --no-pager | head -20
```

目的：核对是否真是 `10.2.3.4`、磁盘是否够、服务名是否为 `myapp`。身份对不上则停止。

可选预检（若走 SFTP 传 jar）：

```
# MCP
sftp_stat(connectionId, path="/opt/app")
sftp_list(connectionId, path="/opt/app")
```

---

## 3. 生产写操作（计划，必须用户明确回复「确认」后才执行）

| # | 操作 | 命令 |
|---|---|---|
| 3.1 | 备份现网 jar | `sudo cp -a /opt/app/myapp.jar "/opt/app/myapp.jar.bak.$(date +%Y%m%d%H%M)"` |
| 3.2 | 上传到临时区 | MCP `sftp_write(connectionId, path="/tmp/myapp.jar", encoding="base64", content=<本地 jar 的 base64>)`；大文件则退到 `scp -O` / 管道 `cat myapp.jar \| ssh ... 'cat > /tmp/myapp.jar'`（密码仍不进 argv，走 MCP 会话） |
| 3.3 | 校验传输 | 远程 `md5sum /tmp/myapp.jar`，与本地 `md5sum myapp.jar` 对比 |
| 3.4 | 原子换入 | `sudo mv /tmp/myapp.jar /opt/app/myapp.jar`（或 `install -m 644`） |
| 3.5 | 再校验 | `md5sum /opt/app/myapp.jar` |
| 3.6 | 重启服务 | `sudo systemctl restart myapp` |
| 3.7 | 看是否起来 | `systemctl status myapp --no-pager`；`systemctl is-active myapp`；`journalctl -u myapp --since "2 min ago" -n 80 --no-pager` |
| 3.8 | 健康检查（如有） | `curl -sS -m 3 http://127.0.0.1:8080/actuator/health` |

失败即停：备份/校验/重启任一步失败，不连续 restart 掩盖问题，把输出交给用户。

---

## 4. 收尾（计划）

```
# MCP
ssh_disconnect(connectionId)
```

不落任何含密码的文件；会话产物（本目录三份 md）中亦无密码。

---

## 5. 密码出现位置（审计）

| 位置 | 是否出现密码 |
|---|---|
| MCP `ssh_connect` 的 `password` 参数 | 是（仅此一处，工具通道，不进 ps） |
| 命令行 / shell 历史 | 否 |
| ssh config / connections.json / 脚本 | 否 |
| `response.md` / `commands.md` / `notes.md` | 否 |
| memory | 否 |
