# 命令记录

说明：环境为模拟评估，主机名不会真正解析/连通。按要求**只发起一次连接尝试**以固化所选路径；其余为后续计划命令，未执行。

口令约定：`Prod#2024` **不写入**任何命令行、脚本或配置文件。下文用 `<OPS01_PASSWORD>` 占位；实际仅通过 SSH 客户端/MCP 的认证参数或 askpass 通道提供。

---

## 1. 本地预检（已部分执行）

```bash
# 创建输出目录；查看本地 jar（当前不存在）
mkdir -p "C:\Users\50822\Desktop\coding coming\ssh-jumpserver-workspace\iteration-1\eval-2-deploy-jar-and-restart-service\without_skill\outputs"
ls -la "C:\Users\50822\Desktop\coding coming\myapp.jar"
```

结果：`myapp.jar` 不存在（评估里假定存在；待用户提供真实路径后再继续）。

计划中的本地检查（待 jar 路径确认后执行）：

```bash
# 路径、大小、摘要（用于与远端比对）
ls -l /path/to/myapp.jar
sha256sum /path/to/myapp.jar
```

---

## 2. SSH / MCP 连接尝试（已执行，仅此一次）

**选用路径**：优先走 SSH MCP `ssh_connect` 到堡垒机入口 `jms.company.com:2222`，用户 `ops01`。选择该方式的原因：

- 密码作为工具认证参数传递，**不经过 shell argv**（避免 `ps` 泄露）；
- 不写 `sshpass -p`、不写 `~/.ssh/config` 明文、不进 git。

**MCP 工具调用**（已执行）：

```
mcp__ssh__ssh_connect(
  host: "jms.company.com",
  port: 2222,
  username: "ops01",
  password: "<见用户提供的口令，此处不落盘>",
  name: "jms-bastion-ops01"
)
```

**结果**：

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

未建立任何会话，未对目标机执行任何命令。

---

## 3. 若连通之后的计划命令（未执行）

### 3.1 到目标机的通道

两种等价方案，视你们 JumpServer 使用方式二选一：

```bash
# 方案 A：OpenSSH ProxyJump（两跳）
# 堡垒机 ops01 认证由 askpass/密钥提供，口令不进命令行
ssh -J ops01@jms.company.com:2222 app@10.2.3.4

# 方案 B：经 MCP 先登堡垒机，再在堡垒机上 ssh 到 10.2.3.4（需 app 凭据或跳板免密）
# mcp__ssh__ssh_connect(host: "jms.company.com", port: 2222, username: "ops01", ...)
# mcp__ssh__ssh_exec(connectionId: "...", command: "ssh app@10.2.3.4 'hostname'")
```

若 JumpServer 要求走其网关/审批通道，改为按你们标准 `ProxyCommand` 或 Web 终端流程，不自创通道。

### 3.2 只读检查（改生产之前）

```bash
# 在 app@10.2.3.4 上
hostname; whoami; id
df -h /opt/app
ls -l /opt/app/myapp.jar
sha256sum /opt/app/myapp.jar
# 服务名待确认，示例为 myapp
systemctl status myapp --no-pager -l
systemctl is-active myapp
ss -lntp | grep -E 'myapp|<端口>'   # 或：curl -sf http://127.0.0.1:<port>/health
journalctl -u myapp -n 50 --no-pager   # 变更前留基线日志
```

### 3.3 备份（覆盖之前必须完成）

```bash
cp -a /opt/app/myapp.jar "/opt/app/myapp.jar.bak.$(date +%Y%m%d%H%M%S)"
ls -l /opt/app/myapp.jar.bak.*
```

### 3.4 上传（经跳板，先临时名，校验后再替换）

```bash
# scp 经 ProxyJump
scp -o ProxyJump=ops01@jms.company.com:2222 \
    /path/to/myapp.jar app@10.2.3.4:/opt/app/myapp.jar.new

# 在目标机校验一致后原子替换
sha256sum /opt/app/myapp.jar.new
mv /opt/app/myapp.jar.new /opt/app/myapp.jar
ls -l /opt/app/myapp.jar
```

（或 `sftp`/`rsync` 经同一跳板；同样先落临时名。）

### 3.5 重启（待用户确认服务名与权限后执行）

```bash
# 示例：systemd；若实际是脚本/其他方式，按用户确认的命令替换
sudo systemctl restart myapp
```

### 3.6 验证与失败回滚

```bash
systemctl is-active myapp
systemctl status myapp --no-pager -l
journalctl -u myapp -n 80 --no-pager
ss -lntp | grep -E 'myapp|<端口>'
# 健康检查按用户提供的路径/端口

# 失败回滚示例
cp -a /opt/app/myapp.jar.bak.<时间戳> /opt/app/myapp.jar
sudo systemctl restart myapp
systemctl is-active myapp
```

### 3.7 收尾

```bash
# 确认无残留临时文件
ls -l /opt/app/
# 确认口令未进入 history / 临时文件（本侧也不使用 shell 内联口令）
```

---

## 4. 明确不会执行的命令

- `sshpass -p 'Prod#2024' ...`、`ssh ...` 后跟口令、任何把口令写进 argv 的形式
- 写入 `~/.ssh/config`、仓库内脚本、`.env` 的明文口令
- `scp -f` / `rsync --delete` 等可能扩大影响面的选项（除非用户明确要求并理解后果）
- 在未备份、未获生产确认前的 `mv` 覆盖与 `systemctl restart`
- 任何 force / 跳过校验的上传或重启
