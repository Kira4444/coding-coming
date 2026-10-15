# 命令与工具调用记录

按时间顺序。密码一律不进命令行；仅在 MCP `ssh_connect(password=...)` 参数中传递，下文不复述明文。

## 已执行

| # | 调用 | 目的 |
|---|---|---|
| 1 | Skill: `ssh-jumpserver` | 加载 JumpServer 直连语法与密码处理规范 |
| 2 | Read `references/jumpserver.md` / `auth.md` / `remote-ops.md` | 确认直连用户名语法、认证优先级、排障命令清单 |
| 3 | MCP `ssh_connect(host=jms.company.com, port=2222, username=root#10.1.2.3#admin, password=<用户提供的堡垒机密码，不落盘不回显>, name=web-01-via-jms)` | 用 JumpServer 直连语法（系统用户#资产IP#登录用户）连 web-01；密码走工具参数，不进 argv |
| 4 | （连接结果）`SSH connection failed: getaddrinfo ENOTFOUND jms.company.com` | DNS 解析失败，未进入认证阶段 |

**未做**：没有改用 `sshpass`、没有把密码写进环境变量再 echo、没有重试其它主机名/IP、没有安装任何工具去“绕过”解析失败。

## 若连接成功，将按序执行（尚未执行）

只读排查，按序：

| # | 远程命令 | 为什么 |
|---|---|---|
| 1 | `hostname; hostname -I 2>/dev/null \|\| ip -4 addr show` | 确认连的是 web-01 / 10.1.2.3，防连错机器 |
| 2 | `uname -a; uptime; df -h; free -m` | 环境与磁盘/内存基线（磁盘满是 nginx 起不来的常见根因） |
| 3 | `systemctl status nginx --no-pager` | 服务是否 failed、启动错误摘要 |
| 4 | `ps aux \| grep -i nginx \| grep -v grep` | 进程是否存活 |
| 5 | `ss -tlnp \| grep -E ':80\|:443'` | 80/443 被谁占用 |
| 6 | `ls -lht /var/log/nginx/ \| head` | 看有哪些日志、何时最后写入 |
| 7 | `tail -n 200 /var/log/nginx/error.log` | 用户指定的错误日志，tail 不 cat 整文件 |
| 8 | `journalctl -u nginx --since "2 hours ago" -n 100 --no-pager` | systemd 拒绝启动的真实原因常在这里 |
| 9 | `nginx -t` | 配置语法/证书路径是否合法（只检查不写盘） |
| 10 | `grep -n '' /etc/nginx/nginx.conf` 或针对性 `grep -n error /var/log/nginx/error.log \| tail -50` | 定位到具体 server/conf 行 |

写操作（**默认不做**，需用户确认后才执行）：

| 命令 | 影响 |
|---|---|
| `systemctl restart nginx` / `reload` | 重启或重载 web 服务，影响该机流量 |
| 编辑 `/etc/nginx/**` | 改配置，需先备份再改 |

## 可选后续连接方式（网络恢复后）

按 skill 探测顺序，若 A 失败再试：

```bash
# A（已试，DNS 失败）
ssh -p 2222 'root#10.1.2.3#admin'@jms.company.com

# C：资产名形式
ssh -p 2222 'root#web-01#admin'@jms.company.com

# D：简化形式
ssh -p 2222 'admin#10.1.2.3'@jms.company.com

# ProxyJump（若堡垒机允许转发到资产 22）
ssh -J admin@jms.company.com:2222 root@10.1.2.3
```

MCP 等价：仍用 `ssh_connect`，`username` 换成上述直连串，`password` 走参数。
