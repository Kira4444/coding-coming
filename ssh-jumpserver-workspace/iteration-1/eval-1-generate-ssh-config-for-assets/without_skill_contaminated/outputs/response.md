配置已经写好了，在 `ssh_config` 里。把它合并进 `~/.ssh/config`（Windows 上是 `C:\Users\<你>\.ssh\config`）之后，直接敲：

```
ssh web-01
ssh web-02
ssh redis-test
```

就能进对应机器，不用再拼 `-p 2222` 和一长串用户名。

## 怎么用

1. 打开（或新建）`~/.ssh/config`，把 `ssh_config` 的内容贴进去。已有内容的话，注意别覆盖掉你原来的 Host 条目。
2. 确认本机有密钥（默认配的是 `~/.ssh/id_ed25519`）。没有就生成一个：

   ```
   ssh-keygen -t ed25519 -C "jumpserver"
   ```

3. 把**公钥**（`~/.ssh/id_ed25519.pub` 的内容）加到 JumpServer：Web 界面 → 个人信息 → SSH 公钥。目标机上的 `authorized_keys` 不用动——资产密码本来就是 JumpServer 托管的。
4. 之后 `ssh web-01` 应该全程无密码直接进。

## 这份配置在干什么

走的是 JumpServer koko 的「用户名嵌资产标识」直连语法，跳过资产树交互菜单：

```
User = root#10.1.2.3#admin
       ──┘   └───┘   └──┘
       系统用户  资产IP   JumpServer登录用户
```

- `HostName` 写的是堡垒机 `jms.company.com`，端口 `2222`
- `User` 里告诉 JumpServer「用 root 登 10.1.2.3，我是 admin」
- 三条 Host 各自指向 web-01 / web-02 / redis-test，IP 分别是 10.1.2.3 / 10.1.2.4 / 10.1.2.5

另外附了两条备用：`ssh jms` 直接进堡垒机菜单（想手动挑机器时用），以及一组注释掉的「资产名」写法——如果 `ssh web-01` 落回了交互菜单而不是直达，说明这套 JumpServer 只认资产注册名，把 `User` 那行换成 `root#web-01#admin` 这种即可。

## 两点要知道

**密码没法写进 ssh config。** 这是 ssh 的设计，不是我漏了。要么配密钥（推荐，上面第 2–3 步），要么每次连接时手输 JumpServer 密码。资产本身（root@10.1.2.x）的密码你不需要知道，JumpServer 会代填。

**先确认连对了机器再动手。** 登进去以后 `hostname` 和 `ip addr` 对一下，特别是以后要拿这些配置去连生产机的时候——连错一台比多问一句代价大得多。

如果 `ssh web-01` 行为不对（落菜单、`Permission denied`、连上就断），把现象发我，常见原因和处理办法我记在 `notes.md` 里了。
