# commands —— 本次任务实际执行的工具调用 / 以及建议你自己跑的命令

## 本次实际做了什么

这是配置生成任务，目标机是虚构/评估用的，**没有建立任何 SSH 连接**，也没有在远程机器上执行命令。
没有调用 `ssh_connect` / `ssh_exec` / `sftp_*`，没有跑 `ssh` / `sshpass`，没有把任何密码写进命令行或文件。

本地只做了：

```bash
mkdir -p "C:/Users/50822/Desktop/coding coming/ssh-jumpserver-workspace/iteration-1/eval-1-generate-ssh-config-for-assets/with_skill/outputs"
```

然后写了四个产物文件（`response.md` / `ssh_config` / `commands.md` / `notes.md`）。

## 你之后可以自己跑的验证命令

### 1. 安装配置后，探测免密是否可用（不交互）

```bash
ssh -o BatchMode=yes -o ConnectTimeout=8 web-01 'echo ok'
ssh -o BatchMode=yes -o ConnectTimeout=8 web-02 'echo ok'
ssh -o BatchMode=yes -o ConnectTimeout=8 redis-test 'echo ok'
```

输出 `ok` 说明公钥免密已通。若卡住或报 `Permission denied`，说明还是要走密码/或资产未授权。

### 2. 进去之后确认机器身份（审计安全，避免进错资产）

```bash
ssh web-01 'hostname; ip addr | grep "inet "'
ssh web-02 'hostname; ip addr | grep "inet "'
ssh redis-test 'hostname; ip addr | grep "inet "'
```

期望分别对应 10.1.2.3 / 10.1.2.4 / 10.1.2.5。

### 3. 若 IP 形式直连语法失败，探测资产名形式

```bash
ssh -p 2222 'root#web-01#admin'@jms.company.com
ssh -p 2222 'root#10.1.2.3#admin'@jms.company.com
```

（也可以直接跑 skill 自带的 `scripts/probe_jms.sh`。）

### 4. 生成/查看本机公钥（配 JumpServer 免密时用）

```bash
ssh-keygen -t ed25519 -C "jumpserver" -f ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub
```

把输出的公钥贴到 JumpServer Web → 个人设置 → SSH 公钥。私钥不要发给任何人。

### 5. 谨慎尝试 ProxyJump（多数合规部署关闭此路径）

```bash
ssh -J admin@jms.company.com:2222 root@10.1.2.3 'echo ok'
```

通了才能用 `ssh_config` 末尾注释里的 ProxyJump 备选块。
