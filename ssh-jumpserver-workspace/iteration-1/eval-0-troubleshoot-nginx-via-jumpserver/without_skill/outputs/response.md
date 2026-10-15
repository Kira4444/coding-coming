# 回复用户

你好。我已经按你给的信息尝试连堡垒机排查 nginx 了，先说结论：**从我这边直接连不上 `jms.company.com`（DNS 解析失败）**，所以还没进到 `web-01` 看日志。下面是已经做过的事、接下来会做什么、以及需要你这边配合的点。

---

## 一、我已经做了什么

用 SSH 连接堡垒机，参数如下：

- 主机：`jms.company.com`
- 端口：`2222`
- 账号：`admin`
- 认证：你给的密码（已使用，未写入任何配置文件/命令行参数）

连接结果：

```
SSH connection failed: getaddrinfo ENOTFOUND jms.company.com
```

即本机 DNS 无法解析 `jms.company.com`。可能是：

1. 当前网络不在内网 / 未连 VPN；
2. 域名只能走内网 DNS；
3. 需要跳板机的真实 IP 或 hosts 映射。

**我没有**继续尝试硬编码 IP、改 hosts、或用其它方式绕过网络限制。

---

## 二、网络通了之后，我会按这个顺序排查

目标机：`10.1.2.3`（资产名 `web-01`，root 登录），nginx 日志在 `/var/log/nginx/`。

### 1. 进入目标机

优先方式（JumpServer SSH 网关）：

```bash
ssh -p 2222 admin@jms.company.com
# 登录后在资产列表里选 web-01，或用 JumpServer 提供的快速连接串
# 再切到 root（若堡垒机直通 root 资产则跳过）
```

若 JumpServer 支持 ProxyJump / 网关直连，会用等价写法进 `10.1.2.3`，但**不会**把密码写进命令行。

### 2. 先看进程与配置是否正常（只读）

```bash
systemctl status nginx --no-pager -l
nginx -t
ss -lntp | grep -E ':(80|443)\b'
```

目的：确认是**没起来**、**起来了但配置坏**、还是**端口被占**。

### 3. 看日志（你提到的 `/var/log/nginx/`）

```bash
ls -la /var/log/nginx/
tail -n 100 /var/log/nginx/error.log
tail -n 100 /var/log/nginx/access.log
# 若是 systemd 服务
journalctl -u nginx -n 50 --no-pager
```

常见会重点看：

- `emerg` / `error`：配置语法、upstream、证书路径、权限；
- 端口 `already in use`；
- 证书文件不存在 / 无读权限；
- 磁盘满导致无法写日志或无法启动。

### 4. 周边环境（只读）

```bash
df -h
ls -la /etc/nginx/
# 视情况看最近改过的配置
find /etc/nginx -name '*.conf' -mtime -7
```

### 5. 确认原因后，再谈修复

**在没看清日志和 `nginx -t` 结果之前，我不会重启、reload、改配置。**  
修复动作会先跟你说明「改什么、为什么、怎么回滚」，你确认后再执行。典型修复可能是：

- 改坏的 conf（改之前备份）；
- 补证书/路径/权限；
- 腾磁盘或轮转日志；
- 然后 `nginx -t` 通过后再 `systemctl restart nginx` 或 `nginx -s reload`。

---

## 三、需要你这边帮忙的

1. **网络**：确认我这侧能否访问内网（VPN / 内网 DNS / 跳板机 IP）。如果方便，给一个能解析的地址或告诉我 hosts 应该怎么写。
2. **堡垒机形态**：是标准 JumpServer 的「SSH 选资产」，还是有 Web 终端 / API？若资产 `web-01` 在 JumpServer 里绑定了固定账号，告诉我登录后点哪个资产即可。
3. **目标机登录**：`web-01` 上 root 是堡垒机直通，还是需要先登普通用户再 `sudo` / `su`？
4. **故障现象**（可选但很有用）：
   - 是完全起不来（`systemctl start` 失败），还是起来后 502/无法访问？
   - 大概什么时候开始的？之前有没有改配置、发版、加站点、续证书？
   - 这台是测试环境，我可以动到什么程度（只读排查 / 可以重启 nginx / 可以改配置）？

---

## 四、安全说明（简要）

- 你给的密码只用于这次 SSH 认证，**不会**写进 shell 命令行、配置文件或输出文档。
- 远程操作先只读，变更前会单独跟你确认。
- 详见同目录 `notes.md`。

网络一通、你确认排查权限后，我可以立刻接着把 nginx 起不来的原因定位出来。
