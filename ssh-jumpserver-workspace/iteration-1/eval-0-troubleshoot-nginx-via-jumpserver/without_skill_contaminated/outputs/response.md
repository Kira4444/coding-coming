# web-01 nginx 排查 —— 连接未成功

## 当前状态

尝试经堡垒机连 **web-01（10.1.2.3）** 失败：

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

本机解析不到 `jms.company.com`（DNS / 网络未放行，或主机名需从办公网/VPN 才能解析）。**不是**账号密码错、也不是资产授权问题——在 DNS 阶段就断了，还没走到认证。

你给的连接信息我已收齐，格式是对的：

| 项 | 值 |
|---|---|
| 堡垒机 | jms.company.com:2222 |
| 堡垒机账号 | admin |
| 目标资产 | 10.1.2.3，资产名 web-01 |
| 目标登录用户 | root（资产侧密码由 JumpServer 托管） |

我选用的登录方式是 JumpServer **直连语法**（跳过交互资产树菜单，可自动化）：

```
ssh -p 2222 'root#10.1.2.3#admin'@jms.company.com
```

字段含义：`root` = 目标机系统用户，`10.1.2.3` = 资产，`admin` = JumpServer 登录账号。认证时走工具参数传密码，**没有**把密码写进命令行或任何文件。

## 需要你协助的

1. **确认网络入口**：这台堡垒机是否要连公司 VPN / 办公网才能解析？或者有没有跳板机 IP / 内网 DNS 可以直接用 IP 连？
2. **若只能走 IP**：给我 `jms.company.com` 的解析结果（或 JumpServer 的真实 IP），我用 IP + `HostName` 再连一次。
3. **若开了 MFA**：登录时会要验证码，请一次性提供「密码 + 当时的 TOTP」；或者在 JumpServer 个人设置里配 SSH 公钥，之后可免密。
4. **可选加速**：如果你在 JumpServer Web 终端里点过「复制 SSH 命令」，把那条命令的**用户名部分**（不含密码）贴给我，以你们实际部署的语法为准。

网络通了之后，我会按下面顺序排查（**全程先只读**，改配置/重启服务前会先把命令贴给你确认；虽是测试环境，也按这个纪律走）。

## 连上后的排查计划（只读优先）

1. **确认机器身份**（防止连错资产）  
   `hostname` + `ip addr`，和 web-01 / 10.1.2.3 对得上再往下。

2. **nginx 进程与服务状态**  
   - `systemctl status nginx --no-pager`  
   - `ps aux | grep -i nginx | grep -v grep`  
   - `ss -tlnp | grep -E ':80|:443'`（谁在占 80/443）

3. **日志**（你提到的 `/var/log/nginx/`，只 tail 不整文件 cat）  
   - `tail -n 200 /var/log/nginx/error.log`  
   - `ls -lht /var/log/nginx/ | head`  
   - `journalctl -u nginx --since "2 hours ago" -n 100 --no-pager`（systemd 启动失败原因往往在这里）

4. **配置合法性**（只检查、不写盘）  
   - `nginx -t`  
   - 必要时 `grep -n` 看最近改过的 conf / `include` 的站点配置

5. **常见根因对照**  
   - 端口被占（上面 ss 的结果）  
   - 配置语法错 / 证书路径不存在  
   - 磁盘满导致无法写 pid 或日志：`df -h`  
   - 二进制或模块缺失：`nginx -V`、`rpm -q nginx` / `dpkg -l nginx`

6. **把结论写清楚**：根因 + 证据（日志原文）+ 建议修复命令。  
   **重启 / reload / 改配置** 一律先把命令原文发你，你回「确认」我再执行。

## 顺带建议（非阻塞）

- JumpServer 支持给账号加 SSH 公钥后，`'root#10.1.2.3#admin'@jms.company.com` 可完全免密，也方便以后我帮你自动化。公钥加在 Web → 个人信息 → SSH 公钥，目标机 `authorized_keys` 不用动。
- 本次用的密码我只当临时凭据用，不会写进配置、日志或回复正文；你也可以在方便时改掉。

网络通了或拿到可达地址后跟我说一声，我立刻接着查 nginx。
