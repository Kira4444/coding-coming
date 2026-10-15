看起来测试环境这台 web-01（10.1.2.3）的 nginx 起不来了，我来帮你查。下面是我这边的进展和需要你配合的点。

## 已经做的

按你们堡垒机的信息（jms.company.com:2222、账号 admin、资产 web-01 / 10.1.2.3、目标机用 root），我走了 JumpServer 的直连语法，一条命令直达目标机、跳过资产树菜单：

```
ssh -p 2222 'root#10.1.2.3#admin'@jms.company.com
```

也准备好了对应的 SSH MCP 连接（host=jms.company.com、port=2222、username=root#10.1.2.3#admin）。认证走工具参数，密码不进命令行、不进配置文件。

**结果：连不上。** 本机解析不了 `jms.company.com`：

```
ssh: Could not resolve hostname jms.company.com: Name or service not known
```

这是在当前这个执行环境里的网络限制（内网域名从这里解析不了），不是堡垒机或 nginx 本身的问题。也就是说我还没真正登上 web-01，nginx 的问题目前还没开始看。

## 需要你这边帮忙

任选其一，我就能继续：

1. **换一个我这边能解析的入口**——比如堡垒机的公网域名 / IP，或者一台我这台机器能直达的跳板。
2. **你在能连内网的机器上跑我把下面的命令**，把输出贴回来，我根据输出远程给判断。
3. **给这台环境开一下出网**，或者把 `jms.company.com` 加到我这台机器的 DNS / hosts。

## 连上之后我会怎么查（都是只读）

登上去之后先确认没连错机器（hostname + IP 要和 web-01 / 10.1.2.3 对得上），再按这个顺序查 nginx：

1. **服务状态**：`systemctl status nginx --no-pager` —— 看是启动失败、退出，还是根本没起来。
2. **配置语法**：`nginx -t` —— 起不来最常见的原因是配置写坏了（少分号、证书路径不对、upstream 不存在）。这里的报错通常就直接指向根因。
3. **启动日志**：`journalctl -u nginx --since "30 min ago" -n 100 --no-pager`，以及你说的 `/var/log/nginx/`：
   - `tail -n 200 /var/log/nginx/error.log`
   - `ls -lht /var/log/nginx/ | head` 看最近写了哪些日志
4. **端口占用**：`ss -tlnp | grep -E ':80|:443'` —— 有时是别的进程占了 80/443，或者 nginx 起了但没监听。
5. **磁盘 / inode**：`df -h; df -i` —— 磁盘满会让 nginx 起不来或写不了日志。
6. **证书与依赖路径**（如果 `nginx -t` 指向这类错误）：确认证书文件、`include` 进来的 conf 是否还在。

## 你在自己机器上可以先跑的（把输出贴给我即可）

```bash
# 经堡垒机直达 web-01（资产名写法，和 IP 写法效果一样）
ssh -p 2222 'root#web-01#admin'@jms.company.com

# 进去之后：
hostname
systemctl status nginx --no-pager
nginx -t
journalctl -u nginx --since "30 min ago" -n 100 --no-pager
tail -n 200 /var/log/nginx/error.log
ss -tlnp | grep -E ':80|:443'
df -h; df -i
```

如果 JumpServer 弹出资产列表菜单而不是直接进 shell，说明你们这套禁用了直连语法——在菜单里选 web-01，或者把 Web 终端里「复制 SSH 命令」按钮给出的字符串发给我，以那个为准。

## 关于改动

目前只做只读排查。如果查出来需要 `nginx -t` 通过后 `systemctl restart nginx`、改配置或补证书，我会先把具体命令和影响面写给你，你确认了我再动。测试环境也一样——不会偷偷重启。

另外：你给的账号密码我只用于本次连接认证，不会写进任何配置文件、日志或命令行历史。如果后面还要经常连，建议在 JumpServer 个人设置里加 SSH 公钥，就能免密直连了，需要的话我可以帮你整理配置（里面只写 host/用户/端口/密钥路径，不写密码）。
