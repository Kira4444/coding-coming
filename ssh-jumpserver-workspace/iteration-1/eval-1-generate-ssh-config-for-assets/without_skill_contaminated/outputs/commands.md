# commands.md — 本次做了 / 会做的操作

本次任务是**生成 ssh config 产出物**，按环境约定不建立真实连接。下面是本地做过的、以及用户配好后可以用来验证的命令。

## 本次实际执行

| 命令 | 目的 | 结果 |
|---|---|---|
| `mkdir -p .../without_skill/outputs` | 建输出目录 | 成功 |
| 写入 `ssh_config` / `response.md` / `commands.md` / `notes.md` | 产出物落盘 | 成功 |

未调用 `ssh_connect` / `ssh_exec` / `sftp_*`，未向 `jms.company.com` 发起任何网络连接。主机名为虚构评测数据。

## 用户配好 config 后的验证命令

探针（免密是否通，不进 shell）：

```bash
ssh -o BatchMode=yes -o ConnectTimeout=8 web-01 'echo ok'
ssh -o BatchMode=yes -o ConnectTimeout=8 web-02 'echo ok'
ssh -o BatchMode=yes -o ConnectTimeout=8 redis-test 'echo ok'
```

输出 `ok` 即免密直达成功。若卡住或 `Permission denied`，先查公钥是否加进了 JumpServer 个人设置。

确认连对了机器（进 shell 后）：

```bash
hostname
ip addr
```

应当分别对应 web-01 / 10.1.2.3、web-02 / 10.1.2.4、redis-test / 10.1.2.5。

## 探测 JumpServer 直连语法（可选，IP 形式不通时）

skill 自带探测脚本，按 A → C → D 顺序试变体：

```bash
bash "C:/Users/50822/Desktop/coding coming/.claude/skills/ssh-jumpserver/scripts/probe_jms.sh"
```

或手动逐个试（失败无副作用，只是连不上或退回菜单）：

```bash
# A. 系统用户#资产IP#登录用户（最通用，当前 config 采用）
ssh -p 2222 'root#10.1.2.3#admin'@jms.company.com

# C. 系统用户#资产名#登录用户（IP 形式落回菜单时换这个）
ssh -p 2222 'root#web-01#admin'@jms.company.com

# D. 简化形式（用默认系统用户）
ssh -p 2222 'admin#10.1.2.3'@jms.company.com
```

## 本机若还没有密钥

```bash
ssh-keygen -t ed25519 -C "jumpserver" -f ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub
```

把输出的公钥贴到 JumpServer Web → 个人信息 → SSH 公钥。**私钥不给任何人，也不写进任何配置文件以外的地方。**
