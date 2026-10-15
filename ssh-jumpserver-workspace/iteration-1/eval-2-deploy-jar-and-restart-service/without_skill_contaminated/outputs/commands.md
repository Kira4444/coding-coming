# commands.md — 实际执行 / 计划执行的命令清单

按时间顺序。标注：`[DONE]` 已执行、`[FAILED]` 已执行但失败、`[PLANNED]` 计划执行、`[BLOCKED]` 需用户确认后才执行。

密码一律不进命令行、不进文件。MCP 工具调用里 password 只作为工具参数传递。

---

## 1. 准备本地工作区

```bash
# [DONE] 创建输出目录
mkdir -p "C:\Users\50822\Desktop\coding coming\ssh-jumpserver-workspace\iteration-1\eval-2-deploy-jar-and-restart-service\without_skill\outputs"

# [DONE] 确认本地 jar（模拟环境中文件不存在，任务说明 pretend myapp.jar exists locally）
ls -la myapp.jar
# → No such file or directory（预期，评估环境）
```

---

## 2. 连接堡垒机 —— JumpServer 直连语法（唯一一次连接尝试）

采用 koko 用户名嵌资产语法（变体 A：`系统用户#资产IP#登录用户`）：

| 字段 | 值 | 含义 |
|---|---|---|
| host | `jms.company.com` | 堡垒机 |
| port | `2222` | koko SSH 入口 |
| username | `app#10.2.3.4#ops01` | 系统用户 `app` + 资产 `10.2.3.4` + JumpServer 登录 `ops01` |
| password | （工具参数，不落盘、不回显） | JumpServer 账号密码 |

### MCP 工具调用

```
mcp__ssh__ssh_connect(
  host: "jms.company.com",
  port: 2222,
  username: "app#10.2.3.4#ops01",
  password: "<JumpServer 密码，仅工具参数>",
  name: "jms-deploy-prod"
)
```

**结果 `[FAILED]`：**

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

原因：堡垒机域名无法解析（模拟环境，主机名不真实；真实场景下也可能是未连 VPN / DNS 配置问题）。**未进入认证阶段**，未尝试其他语法变体、未做任何 hack 绕过。

按任务要求：只尝试连接一次，然后停下记录。

---

## 3. 连上之后的计划命令（全部 `[PLANNED]` / `[BLOCKED]`）

以下按生产发布标准流程排列。阶段 0 可在连通后直接做；阶段 1 起为生产写操作，**必须用户明确确认后才执行**。

### 阶段 0 —— 只读确认身份与现场 `[PLANNED]`

```bash
# 确认登录的确是 10.2.3.4，对不上立即停止
hostname
hostname -I 2>/dev/null || ip -4 addr show | awk '/inet /{print $2}'

# 现场概览
uname -a; uptime
df -h /opt /tmp
free -m

# 当前服务状态与目录现状
systemctl status myapp --no-pager || true
ls -la /opt/app/
command -v systemctl; command -v java
```

### 阶段 1 —— 备份当前包 `[BLOCKED: 需生产写操作确认]`

```bash
cp -a /opt/app/myapp.jar "/opt/app/myapp.jar.bak.$(date +%Y%m%d%H%M)"
ls -la /opt/app/
```

（若目录下实际文件名不同，按实际名字备份。）

### 阶段 2 —— 上传新包并校验 `[BLOCKED: 需生产写操作确认]`

```bash
# 本地
md5sum myapp.jar
```

```
# 首选：SSH MCP SFTP 上传
mcp__ssh__sftp_write(
  connectionId: "<连接 id>",
  path: "/opt/app/myapp.jar",
  content: "<jar 二进制，encoding=base64>",
  mode: 0o644
)
```

```bash
# 降级：SFTP 不可用时（koko 对 SFTP 支持有限），管道写入
# 注意：密码不进此命令行，认证由已建立的 SSH 会话/SSH_ASKPASS 承担
cat myapp.jar | ssh ... 'cat > /opt/app/myapp.jar'

# 远程校验，必须与本地一致
md5sum /opt/app/myapp.jar
```

不一致则停止，不进入重启。

### 阶段 3 —— 重启服务 `[BLOCKED: 生产重启，必须用户明确「确认」]`

```bash
sudo systemctl restart myapp
```

若 myapp 非 systemd 管理，先探测再回来对命令：

```bash
ps aux | grep -i myapp | grep -v grep
systemctl list-units --type=service | grep -i myapp
docker ps 2>/dev/null | grep -i myapp
```

### 阶段 4 —— 验证起来了 `[PLANNED]`

```bash
systemctl is-active myapp
systemctl status myapp --no-pager
ss -tlnp | grep -E ':(8080|80|443)\b'
curl -sI localhost:8080/health 2>/dev/null | head
journalctl -u myapp --since '2 min ago' -n 80 --no-pager
tail -n 50 /var/log/myapp/app.log 2>/dev/null
```

确认：进程 active、端口在听、健康检查/日志无异常。起不来则把日志尾部交给用户，**不连续盲目 restart**。

### 收尾 `[PLANNED]`

```
mcp__ssh__ssh_disconnect(connectionId: "<连接 id>")
```

---

## 4. 明确没有执行的命令

以下一律**未执行、也不会在未确认前执行**：

| 命令 | 原因 |
|---|---|
| `sshpass -p '<密码>' ssh ...` | 密码会进 `ps` 可见的 argv |
| 任何把密码写进 `-o`、脚本、`~/.ssh/config` 的做法 | 密码落盘 |
| `systemctl restart myapp`（现在） | 生产重启，未获用户确认 |
| 覆盖 `/opt/app/myapp.jar`（现在） | 生产写操作，未获用户确认 |
| `chmod 777`、关 SELinux/AppArmor | 安全红线 |
| 绕过 JumpServer 审计 / 关日志 | 合规红线 |
| 反复尝试连接或换语法「撞」进去 | 任务要求只试一次；生产环境不折腾 |
| 安装工具、改网络配置让连接「变通成功」 | 任务明确禁止 |
