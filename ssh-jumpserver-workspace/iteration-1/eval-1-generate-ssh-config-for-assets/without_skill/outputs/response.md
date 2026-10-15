# SSH config for JumpServer test assets

已按 JumpServer（`jms.company.com:2222`，账号 `admin`）生成一份 `ssh config`，装好之后直接：

```bash
ssh web-01
ssh web-02
ssh redis-test
```

就能登上去，不用再敲端口 / 跳板 / 用户那一串参数。

## 怎么装

把 `ssh_config` 的内容保存到：

| 平台 | 路径 |
|---|---|
| Linux / macOS | `~/.ssh/config` |
| Windows (OpenSSH) | `C:\Users\<你的用户名>\.ssh\config` |

然后收紧权限（Linux/macOS）：

```bash
chmod 600 ~/.ssh/config
```

Windows 上如果是用 Git Bash / WSL 操作，同样 `chmod 600`；原生 OpenSSH 对权限要求没那么严。

## 装完之后

```bash
ssh web-01        # → 10.1.2.3  (root)
ssh web-02        # → 10.1.2.4  (root)
ssh redis-test    # → 10.1.2.5  (root)
```

也可以直接带命令：

```bash
ssh web-01 'uptime'
scp report.txt web-02:/tmp/
```

额外给了一条 `ssh jms-menu`，会进 JumpServer 的资产选择菜单，方便临时挑别的资产。

## 这份配置的工作方式

JumpServer 2222 端口是 KoKo SSH 网关，不是普通 SSH 跳板机。它解析登录用户名的格式是：

```
<JumpServer用户>@<系统用户>@<资产IP或名称>
```

所以 `Host web-01` 块里写的是：

```
HostName jms.company.com
Port 2222
User admin@root@10.1.2.3
```

也就是说：所有 `ssh web-01` 实际都是「连到 JumpServer，然后由它把你转到 10.1.2.3 的 root」。资产本身并不直接暴露 22 端口给你。

## 你还需要做的一件事：配好登录凭据

**不要把密码写进 config。** 两条路选一条：

1. **推荐：SSH 公钥**
   ```bash
   ssh-keygen -t ed25519 -C "your.name@company"
   cat ~/.ssh/id_ed25519.pub
   ```
   把公钥粘贴到 JumpServer 界面 → 个人信息 → SSH 公钥。之后 `ssh web-01` 就免密了。

2. **JumpServer 只开密码登录**：把 config 里的 `IdentityFile` / `IdentitiesOnly` 两行删掉，每次会提示输密码。能用，但每次都要敲，且没法配合脚本。

配置里已经写了 `IdentityFile ~/.ssh/id_ed25519`，如果你的密钥叫别的名字（比如 `id_rsa`），把路径改一下即可。

## 如果连不上，先核对这几点

1. **资产名/ID 用 IP 还是名称**：默认写的是 IP（`admin@root@10.1.2.3`）。如果你们 JumpServer 禁用了 IP 路由，把三处 `User` 改成 `admin@root@web-01` 这种资产名形式（config 末尾的 Notes 里有模板）。
2. **系统用户名**：假设都是 `root`。如果有资产不是 root，对应 `User` 的中段改掉。
3. **JumpServer 账号名**：假设是 `admin`。如果实际登录 JumpServer 的账号不是 `admin`，把 `User` 的第一段换成你的 JumpServer 用户名。
4. **端口**：按你说的 2222。如果实例上 KoKo 被改过端口，同步改 `Port`。
5. **第一次连会问 host key**：输入 `yes` 确认，之后不再问。

## 想省事还可以开连接复用

同一台机上连多个资产时，可以走 `ControlMaster` 复用 TCP 连接（config 里暂未开启，避免和你们网络策略冲突）。需要的话在 `Host jms` 块加：

```
ControlMaster auto
ControlPath ~/.ssh/cm-%r@%h:%p
ControlPersist 10m
```

## 完整配置

见同目录 `ssh_config`。
