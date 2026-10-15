已经帮你生成好了 SSH 配置，放进去之后 `ssh web-01` / `ssh web-02` / `ssh redis-test` 三条命令就能直达，不用再敲一堆参数。

## 配置内容

见同目录的 `ssh_config`。核心是 JumpServer（koko）的**直连用户名语法**——把「系统用户 + 资产 + 登录用户」嵌进 `User` 字段，认证过堡垒机后自动落到目标机，跳过资产树菜单：

```
Host web-01
  HostName jms.company.com
  Port 2222
  User root#10.1.2.3#admin
  IdentityFile ~/.ssh/id_ed25519
```

三台机器对应关系：

| 本地命令 | 资产 | 目标 IP | 系统用户 | JumpServer 登录用户 |
|---|---|---|---|---|
| `ssh web-01` | web-01 | 10.1.2.3 | root | admin |
| `ssh web-02` | web-02 | 10.1.2.4 | root | admin |
| `ssh redis-test` | redis-test | 10.1.2.5 | root | admin |

## 安装方式

把 `ssh_config` 的内容合并进你的 `~/.ssh/config`（Windows 上是 `C:\Users\<你>\.ssh\config`）。如果已有别的 Host 条目，保留它们，只把上面这些 Host 块加进去。没有这个文件就直接复制过去：

```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
# 编辑 ~/.ssh/config，把 ssh_config 内容贴进去
chmod 600 ~/.ssh/config
```

## 连上之后先确认机器

JumpServer 会话是审计录屏的，进错机器很麻烦。第一次连上每台机时先跑一下：

```bash
hostname; ip addr | grep 'inet '
```

和表里的资产对得上再干活。

## 密码与免密

**ssh config 不能存密码**——这是协议设计，不是我漏写。所以：

- **目标机（root@10.1.2.x）的密码你不用管**，由 JumpServer 托管，认证完堡垒机它会自动填。
- **JumpServer 账号（admin）的认证**你需要自己解决一次，两种选法：
  1. **推荐：SSH 公钥免密。** 把本机公钥（`~/.ssh/id_ed25519.pub`）贴到 JumpServer Web 界面 → 个人设置 → SSH 公钥。之后 `ssh web-01` 完全免密，配置里的 `IdentityFile` 就是干这个的。没有密钥的话本地跑 `ssh-keygen -t ed25519` 生成一对。
  2. **密码认证：** 每次连接时自己在密码提示里输入 JumpServer 密码。开 MFA 的话后面还会要验证码。

配置里故意没有、也不会出现任何密码或口令字段。

## 如果连不上

先试 IP 形式（当前配置用的），不行就把对应条目的 `User` 换成资产名形式：

```
User root#web-01#admin
User root#web-02#admin
User root#redis-test#admin
```

`ssh_config` 文件末尾注释里已经写好这两套备选。若提示 `Permission denied`，多半是 JumpServer 账号没勾选这三台资产的授权，去 Web 界面的资产授权里看一眼。若 2222 端口不通，确认一下你们 JumpServer 的 SSH 端口是不是默认的 2222。

## 还有一件事

配置里 `IdentityFile` 写的是 `~/.ssh/id_ed25519`。如果你的密钥路径不一样（比如 `id_rsa`），改一下这行即可。
