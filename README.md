# Coding Agent

部署在团队服务器上的 Web 版 coding agent。对接 DeepSeek / 智谱 GLM（OpenAI 兼容协议），在隔离沙箱里读代码、改代码、跑测试、提交改动。

**许可证**：Apache-2.0

> 当前状态：M1 骨架已可编译运行。沙箱、审批、SSE 对话链路已通，尚未在真实模型上端到端联调。

---

## 它做什么

给一个代码库和一句话需求，agent 会：

1. 用 `glob` / `grep` / `read_file` 搞清楚代码现状
2. 用 `write_file` 的 **search/replace 精确替换**改代码
3. 跑测试验证
4. 每完成一个阶段 `git_commit` 到会话专属分支
5. 全程在网页上流式展示，写操作等你点确认

改动最终以 **diff 视图 + `.patch` 下载**的形式交回给你。

**agent 不会自动 push。** 验收后由你手动点「发布」，把会话分支推到项目的**发布分支**（支线仓库）——见下文「Git 与发布」。

---

## 快速开始

### 1. 准备

```bash
cp .env.example .env

# 必填：凭据加密密钥。缺失时服务拒绝启动。
CODING_AGENT_SECRET_KEY=$(openssl rand -base64 32)

# 必填（至少一个）：模型 API key
MIMO_API_KEY=sk-xxx
# 或 DEEPSEEK_API_KEY / GLM_API_KEY
```

**`CODING_AGENT_SECRET_KEY` 是必需项**——项目推送凭据（deploy token / SSH key）入库前用它加密。
密钥缺失时服务**拒绝启动**，而不是把凭据明文存进数据库。换密钥会导致已存凭据解不开，要重新填。

### 2. 一键起

```bash
docker compose up -d
```

打开 http://localhost

**默认账号**：`admin` / `admin123`（首次启动自动创建）。登录后请立即在「设置」里改掉。

### 页面

| 页面 | 路径 | 说明 |
|---|---|---|
| 登录 | `/login` | 用户名密码，令牌存本地 |
| 项目 | `/projects` | 列表、新建；点进详情可改代码来源、沙箱镜像、模型覆盖 |
| 项目详情 | `/projects/:id` | 代码来源 · Agent 配置 · MCP · Skills · **Git 与发布** · 会话列表 |
| 会话 | `/sessions` | 全部会话，可按项目筛选 |
| 对话 | `/sessions/:id` | 对话流 + 工具卡片 + 审批；「改动」tab 看 diff、下 patch |
| 设置 | `/settings` | 账号改密、模型 provider（key 打码）、沙箱配置只读 |

### 3. 本机开发（无需装数据库）

本机开发用 **H2（MySQL 兼容模式）**，不必先装 MySQL。生产仍是 MySQL——见上文一键起。

```bash
# 前置：JDK 17+、Maven 3.9+、Node 20+

# 后端（dev profile = H2 + 本地沙箱）
cd backend
CODING_AGENT_SECRET_KEY=dev-secret-do-not-use-in-prod \
MIMO_API_KEY=sk-xxx \
CODING_AGENT_DEFAULT_PROVIDER=mimo \
mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments=--server.port=8280

# 前端
cd frontend
npm install
npm run dev     # http://localhost:5173
```

> **Windows 用户注意端口**：Hyper-V / WSL2 会预留若干 TCP 端口区间，其中
> `8054–8153` 把 **8080** 包住了——表现为「Address already in use」但 `netstat`
> 显示无人监听。查：
>
> ```bash
> netsh interface ipv4 show excludedportrange protocol=tcp
> ```
>
> 本机开发用 **8280**（`8254–8324` 是空的）。容器部署走 Linux，不受影响。

> **`dev` profile 的沙箱驱动是 `local`**：agent 的命令以服务进程身份直接跑在你机器上，
> **无任何隔离**。只在本机开发用，不要带到服务器上。

#### 不带数据库跑起来会缺什么

- 建表语句不写 `CREATE DATABASE` / `USE`，建库由部署方负责（docker-compose 的
  `MYSQL_DATABASE`，或手工 `CREATE DATABASE coding_agent`）
- 首次启动会播种默认管理员账号 `admin`（见 `DataBootstrap`），否则项目/会话会因外键失败
- 没有模型 API key 时，agent 能跑起来但调模型会返回「模型 API 鉴权失败」——链路是通的，等 key

---

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  frontend (Vue3 + Vite)                                 │
│    对话流 · 工具卡片 · 审批交互 · diff 视图               │
└────────────────────────┬────────────────────────────────┘
                         │ HTTP + SSE
┌────────────────────────▼────────────────────────────────┐
│  backend (Spring Boot 3 + WebFlux)                      │
│                                                         │
│  web/          HTTP API、SSE、审批入口                   │
│  agent/        agent loop、工具集、上下文预算            │
│  model/        OpenAI 兼容 provider 抽象                 │
│  sandbox/      沙箱驱动（local / docker）                │
│  storage/      MyBatis-Plus → MySQL                     │
└──────┬───────────────────────────────────┬──────────────┘
       │                                   │
       ▼                                   ▼
┌──────────────┐                  ┌──────────────────────┐
│  MySQL       │                  │  沙箱容器（每会话一个）│
│  会话/历史/  │                  │  bind mount 工作区    │
│  审计/用量   │                  │  资源限额 · 网络策略   │
└──────────────┘                  └──────────────────────┘
```

### 模块边界

| 包 | 职责 |
|---|---|
| `web` | HTTP API、SSE 推送、审批入口、错误响应 |
| `agent` | agent loop、工具注册与执行、上下文截断、审批策略 |
| `model` | LLM provider 抽象，DeepSeek / GLM / 自部署 vLLM 同一套接口 |
| `sandbox` | 执行环境生命周期；`local` 与 `docker` 两个实现可切换 |
| `storage` | MyBatis-Plus 实体与 mapper |

单体部署，但边界清晰——将来要拆成 Web 服务 + Agent Runner，按包切开即可。

---

## 模型接入

任何 OpenAI 兼容接口都能接，配置成 provider 即可：

```yaml
coding-agent:
  model:
    default-provider: deepseek
    providers:
      deepseek:
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-chat
      glm:
        base-url: https://open.bigmodel.cn/api/paas/v4
        api-key: ${GLM_API_KEY}
        model: glm-4.5
      # 自部署 vLLM / SGLang 同理
      local-vllm:
        base-url: http://10.0.0.5:8000/v1
        api-key: none
        model: DeepSeek-V3
    # 按任务类型分流（结构已预留，MVP 不填也能跑）
    # task-overrides:
    #   plan: glm/glm-4.5
    #   summary: deepseek/deepseek-chat
```

---

## Git 与发布

每个项目都有独立的 git 能力：会话专属分支、阶段自动提交、diff / patch 交付，以及**发布到支线仓库**。

「支线仓库」= **同一个远程仓库上的发布分支**。例如主干是 `main`，改动推到 `release/2026-10`。

### 语义

```
git push <remote> <会话分支>:<发布分支>
```

- 会话分支是发布分支的后继时快进推送
- **两者分叉时明确拒绝并说明原因，不自动 force 覆盖**——发布是对外动作，覆盖得人拍板
- 发布**只由人在界面上点按钮触发**，agent 不会自动推送

### 项目级配置

| 配置项 | 说明 |
|---|---|
| 发布分支 | 目标分支名，如 `release/2026-10` |
| 远程名 | 默认 `origin` |
| 凭据类型 | `TOKEN`（HTTPS deploy token）或 `SSH_KEY` |
| HTTPS 用户名 | token 模式用，通常 `git` 或 `oauth2` |
| 推送凭据 | AES-256-GCM 加密入库，界面只显示掩码 |

不填凭据则复用宿主机已有的 git 配置。

### 界面

项目详情 → **「Git 与发布」**：当前分支、远程地址、待发布提交数、最近提交列表、发布配置、发布按钮与结果。

---

这一节请认真读。coding agent 能跑 shell，权限模型是产品的一部分。

### 沙箱驱动

| 驱动 | 隔离性 | 适用 |
|---|---|---|
| `local` | **无隔离**，命令以服务进程身份直接跑在宿主机 | 仅限开发环境 |
| `docker` | 一会话一容器，资源限额 + 非 root 用户 | 服务器部署必须用这个 |

**服务器部署必须设 `CODING_AGENT_SANDBOX_DRIVER=docker`。**

### Docker socket 的风险

`docker-compose.yml` 把 `/var/run/docker.sock` 挂给了 backend —— 这是为了在宿主机上开沙箱容器。代价是 **backend 进程拥有宿主机 root 级权限**（能开特权容器、能挂载任意宿主机路径）。

缓解措施：

- 换用 **rootless Docker**，或给 backend 单独起一个 Docker 实例（DinD）
- 至少把 backend 和 MySQL 等服务跑在独立的主机上
- 用 `no-new-privileges`、非 root 用户、资源限额约束沙箱容器

### 网络策略

沙箱默认**允许公网出网**（否则 `npm install` 跑不了），**默认禁内网**（防提示注入诱导 agent 扫内网）。

「禁内网」依赖宿主机 Docker 网络配置，本项目只提供 `network: public | none` 开关。要更细粒度的出网控制，需在宿主机上配 iptables / 自定义 Docker 网络。

### 提示注入

仓库里的代码、注释、README 都可能含有针对 agent 的恶意指令。这是当前无法根除的风险。

已有的防线：

- 分级审批：写操作与危险 shell 命令需人工确认
- 命令审计日志：谁、何时、让 agent 跑了什么，全程可追溯
- 输出截断：避免超长输出淹没上下文
- 系统提示中明令禁止外访内网、外泄密钥

### 密钥

- 模型 API key 走环境变量，不入库
- **推送凭据（deploy token / SSH key）AES-256-GCM 加密入库**，界面只显示掩码，不提供查看原值
- 凭据注入 git 时**不进命令行**（`ps` 可见）、**不进 git config**（会随仓库泄露）
  - HTTPS token → `GIT_ASKPASS` + 环境变量，脚本本身不含密钥
  - SSH 私钥 → 临时文件 0600 + `GIT_SSH_COMMAND -i`，用完即删
- `.env` 已在 `.gitignore` 中，切勿提交
- agent 读到硬编码密钥时只会指出存在，不会把内容写进回复

---

## 配置

全部通过环境变量注入，见 [`.env.example`](.env.example)。关键项：

| 变量 | 默认 | 说明 |
|---|---|---|
| `CODING_AGENT_SECRET_KEY` | **无，必填** | 推送凭据加密密钥。缺失则服务拒绝启动 |
| `CODING_AGENT_SANDBOX_DRIVER` | `local` | 沙箱驱动，服务器必须 `docker` |
| `CODING_AGENT_SANDBOX_NETWORK` | `public` | 沙箱网络策略 |
| `CODING_AGENT_SANDBOX_CPU` | `2` | 单容器 CPU 上限 |
| `CODING_AGENT_SANDBOX_MEMORY` | `4g` | 单容器内存上限 |
| `CODING_AGENT_MAX_CONCURRENT` | `4` | 同时执行的 agent 会话数，超出排队 |
| `CODING_AGENT_MAX_TURNS` | `40` | 单会话最大工具调用轮次 |
| `CODING_AGENT_TOKEN_BUDGET` | `500000` | 单会话 token 预算 |

---

## 数据模型

```
project ──< session ──< message
                │
                └──< audit_log
```

- **项目**：一个代码库（git URL 或服务器本地路径）
- **工作区**：项目在服务器上的一份落盘 clone，一会话一份
- **会话**：一次对话，绑定工作区与专属分支
- **工作区保留 7 天**（可配）后回收磁盘；回收后会话只能回看历史

---

## 已知取舍

这些是设计时就接受的代价，不是遗漏：

1. **MySQL 存消息流** —— JSON 字段可行，但全文检索与将来的向量检索会比 PG 吃力，届时可能要加索引表
2. **不做自动上下文压缩** —— 长会话会退化，靠「新开会话」兜底；截断与预算上限是硬防线
3. **一会话一 clone** —— 磁盘成本随会话数线性增长，靠 7 天回收兜底；大仓库首次 clone 慢
4. **Docker socket** —— 见上文「安全」
5. **agent 能跑 shell 且默认公网出网** —— 提示注入风险真实存在，审计日志是事后追溯底线

---

## 开发

```bash
# 后端测试
cd backend && mvn test

# 前端类型检查与构建
cd frontend && npm run typecheck && npm run build
```

**编译目标是 Java 17**（Maven 本机跑在 17 上）。JDK 21 可正常运行，但代码里未使用 21 特性。

---

## 路线图

MVP 已包含：Web UI、git/本地路径导入、会话分支与 patch 下载、文件读写搜索、shell、git commit、Docker 沙箱、SSE 流式、分级审批、审计日志、token 用量。

**明确不做（二期）**：git push / PR 集成、浏览器控制、MCP 外部工具、自动上下文压缩、OIDC/LDAP、用户自带 API key、向量索引 / RAG、真终端 xterm.js、多人协作同一会话、K8s/Helm、成本报表与计费配额。

详见 [`DESIGN.md`](DESIGN.md)。
