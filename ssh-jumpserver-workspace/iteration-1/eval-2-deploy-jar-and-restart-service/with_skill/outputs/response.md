# 回复用户

已按堡垒机路径尝试连接生产机，**连接本身没通**（见下方说明）。另外你这次是**生产环境**的发布+重启，按运维纪律，**真正写盘/重启前需要你确认一次**，我不会直接执行。下面是连接方式、现状、以及我准备执行的完整命令清单。

---

## 1. 连接方式（已尝试）

用 JumpServer（koko）直连语法，经 SSH MCP 建连——密码只走工具参数，**不会出现在命令行、配置文件或本回复里**：

| 项 | 值 |
|---|---|
| 堡垒机 | `jms.company.com:2222` |
| JumpServer 账号 | `ops01` |
| 目标资产 | `10.2.3.4` |
| 目标机登录用户 | `app` |
| 直连用户名 | `app#10.2.3.4#ops01`（系统用户#资产IP#登录用户） |

尝试结果：

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

**原因**：本机解析不了 `jms.company.com`（DNS/内网路由）。不是认证失败，也不是资产权限问题。

**你需要做的（连接层）**：
- 确认本机能否解析/直达堡垒机：`ping jms.company.com` 或 `nslookup jms.company.com`
- 如果必须挂 VPN / 跳板网络才能到堡垒机，先连上再让我重试
- 如果堡垒机 SSH 端口不是 2222，或 koko 直连语法被关了，告诉我实际端口，或从 JumpServer Web 界面「复制 SSH 命令」把权威命令贴给我（**贴命令时请把密码字段去掉**）

网络通了之后我立刻重试。下面是**连上之后**我会做的事。

---

## 2. 连上后的只读确认（不需要你再确认）

先核对机器身份，防止连错生产机：

```bash
hostname
hostname -I 2>/dev/null || ip -4 addr show | awk '/inet /{print $2}'
uname -a
df -h /opt /tmp
systemctl status myapp --no-pager | head -20
```

确认 `hostname` / IP 与 `10.2.3.4` 一致、磁盘放得下 jar、`myapp` 服务名正确。**对不上就停，不往下走。**

---

## 3. 生产写操作 —— 请你确认后我才执行

你要求的是：上传 `myapp.jar` 到 `/opt/app/`，重启 `myapp`。这两步在生产都属于写操作（覆盖正在运行的二进制 + 重启服务），按生产纪律需要你明确回复「**确认**」才执行。

计划执行的命令如下（届时会按实际路径/服务名微调，以只读检查结果为准）：

```bash
# 1) 备份当前版本，保证可回滚
sudo cp -a /opt/app/myapp.jar "/opt/app/myapp.jar.bak.$(date +%Y%m%d%H%M)"

# 2) 上传新包（SFTP 到 /tmp，再原子换入 /opt/app/）
#    —— 本地 myapp.jar 经 SFTP 传到 /tmp/myapp.jar，不在命令行暴露内容
#    —— 然后：
sudo mv /tmp/myapp.jar /opt/app/myapp.jar

# 3) 校验（和本地 md5 对比）
md5sum /opt/app/myapp.jar
# 本地对应：md5sum myapp.jar

# 4) 重启服务（明确告知：重启的是 myapp，生产实例 10.2.3.4）
sudo systemctl restart myapp

# 5) 验证起来了没有
systemctl status myapp --no-pager
systemctl is-active myapp
journalctl -u myapp --since "2 min ago" -n 80 --no-pager
# 如有健康检查接口，再补：
# curl -sS -m 3 http://127.0.0.1:8080/actuator/health || true
```

**影响面**：
- 覆盖 `/opt/app/myapp.jar`（先备份，可回滚）
- 重启生产服务 `myapp`，期间该实例会短暂不可用
- 目标机：`10.2.3.4`，用户 `app`（必要时 sudo）

**请回复「确认」后我再执行第 3 节。** 若只想我先连上做只读检查、不写任何东西，回复「先只读」即可。

---

## 4. 关于密码

你给的密码我只用于本次 MCP 建连参数，**不会**：
- 写进任何命令行 / ssh config / 脚本 / 日志 / 本次产物
- 在回复里复述
- 存进 memory 或 connections.json

后续如果希望免密，推荐在 JumpServer 个人资料里挂 SSH 公钥，我可以帮你生成密钥并给出公钥文本（私钥你本地留好，我不会打印私钥）。

---

## 5. 当前状态小结

| 项 | 状态 |
|---|---|
| 连接方式 | JumpServer 直连语法 + SSH MCP（密码不落 argv） |
| 连接结果 | 失败：`jms.company.com` DNS 解析失败 |
| 写操作 | **未执行**，等你确认 |
| 密码 | 未写入任何文件/命令行/回复 |

网络可达后告诉我一声，或直接回「确认」+ 网络已通，我按上面清单执行。
