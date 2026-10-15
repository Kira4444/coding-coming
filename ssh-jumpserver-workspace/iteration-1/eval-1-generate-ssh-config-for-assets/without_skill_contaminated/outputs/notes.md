# notes.md — 需要留意的事

## 密码处理（重要）

**ssh config 不能存密码**——没有 `Password` 字段，这是 OpenSSH 的设计。任何人让你把密码写进 `~/.ssh/config`、脚本或 `connections.json`，都不要照做。

本次任务**不涉及任何真实凭据**。用户口述的 JumpServer 密码（如果给过）属于临时凭据：用完即弃，不进命令行、不进文件、不在回复里复述。

免密的正确路径是 SSH 密钥：

1. 本机 `ssh-keygen -t ed25519`（配好了就用现有的）
2. 公钥（`.pub`）加到 **JumpServer 账号**的个人设置（Web → 个人信息 → SSH 公钥）
3. 目标机的 `authorized_keys` **不用动**——资产密码由 JumpServer 托管，本地只认证堡垒机这一层

配好后 `ssh web-01` 全程无密码。没配的话每次连接会提示输 JumpServer 密码（不是 root 密码）。

## 用户还需要做的事

- [ ] 把 `ssh_config` 合并进 `~/.ssh/config`（Windows: `C:\Users\<你>\.ssh\config`），注意不要覆盖已有 Host 条目
- [ ] 确认 `~/.ssh/id_ed25519` 存在；没有就 `ssh-keygen -t ed25519 -C "jumpserver"`
- [ ] 把公钥加到 JumpServer 个人设置里的 SSH 公钥栏
- [ ] 跑一遍 `ssh -o BatchMode=yes -o ConnectTimeout=8 web-01 'echo ok'` 验证免密
- [ ] 如果弹了密码提示且不想每次输：回上一步检查公钥是否保存成功；也可以 `ssh-add` 把密钥加进 agent

## 配置设计上的取舍

| 决策 | 原因 |
|---|---|
| 用直连语法 `root#<IP>#admin`，不用交互菜单 | 菜单需要 PTY + 手输编号，`ssh web-01` 一条命令直达才是用户要的 |
| 资产标识写 **IP** 而非资产名 | IP 形式（变体 A）兼容性最高，不依赖 JumpServer 里的命名 |
| 附了资产名写法作注释备用 | 部分私有化部署只认注册名；IP 形式落回菜单时改一行就能用 |
| `HostName` 是堡垒机不是 10.1.2.x | 直连语法的原理是「连堡垒机、用户名里带资产标识」，HostName 必须是 jms |
| `IdentitiesOnly yes` | 避免 ssh 把本机一堆密钥都试一遍，拖慢连接、触发锁定 |
| `ServerAliveInterval 30` + `CountMax 3` | 经堡垒机的长连接容易被中间设备掐断，保活一下 |
| `StrictHostKeyChecking accept-new` | 首次连不被交互式 yes/no 卡住；已记录的 host key 变更仍会拦（防 MITM） |
| 额外给了 `Host jms` 条目 | 想手动进资产树挑机器时用，不影响三个直达条目 |

## 常见故障

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `ssh web-01` 落回资产树菜单 | 直连语法被禁用，或资产标识写法不认 | 把 `User` 换成 `root#web-01#admin` 试资产名形式；还不行就只能菜单模式 |
| `Permission denied` | JumpServer 账号/密码错，或 admin 无这些资产的授权 | 核对账号；在 JumpServer Web 确认资产授权给 admin |
| 连上后立刻断开 | 资产标识写错、资产禁用、系统用户不存在 | 对照 IP/资产名两种写法；Web 界面看资产列表 |
| 提示「请使用 Web 终端」 | 该资产禁用了 SSH 协议 | 只能走 JumpServer Web UI |
| 端口 2222 不通 | koko 端口被改过，或防火墙 | 确认 JumpServer 的 SSH 端口（默认 2222，有的改 22） |
| `sftp` 报错 | koko 对 SFTP 支持有限，或进了菜单模式 | 试 `scp -O`；或走 ProxyJump（若部署允许） |
| 密码后还要验证码 | JumpServer 开了 MFA/TOTP | 手输验证码，或在 JumpServer 加公钥并开启公钥免 MFA |

## 安全提醒

- **不要**用 `sshpass -p` 之类把密码塞进命令行——`ps` 看得见
- **不要**把密码写进配置、脚本、日志、`connections.json`
- JumpServer 会话是**审计录屏**的，这是它的存在意义，不要试图绕过
- 生产机操作（删文件、改配置、重启核心服务）先停下来确认目标机是哪台（`hostname` + `ip addr`）再动手
- 资产名 web-01 / web-02 / redis-test 看着是测试机，但真用这份配置连生产时，`Host` 别名和真实资产务必自己再核对一遍

## 如果 JumpServer 允许 ProxyJump（可选更优解）

部分部署允许堡垒机做普通跳板，SFTP/SCP 更干净：

```
Host web-01
  HostName 10.1.2.3
  User root
  ProxyJump admin@jms.company.com:2222
```

探一下（通了才换）：`ssh -J admin@jms.company.com:2222 root@10.1.2.3 'echo ok'`
多数合规部署关了这个口子，失败很正常——当前的直连语法方案不依赖它。
