# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

部署在团队服务器上的 Web 版 coding agent。用户指定一个代码库、描述一个任务，agent 就去读代码、用 search/replace 改代码、跑测试、提交到会话专属分支。前端 Vue3，后端 Spring Boot WebFlux。

产品决策见 `DESIGN.md`，部署说明见 `README.md`。

## 常用命令

### 后端（Java 17 + Maven）

```bash
cd backend

mvn test                                    # 全部测试
mvn test -Dtest='ShellToolTest'             # 单个测试类
mvn test -Dtest='WriteFileToolTest#唯一匹配时精确替换'   # 单个测试方法
mvn -q -DskipTests compile                  # 只编译

# 本机开发：项目内便携 MySQL（mysql/ 目录）、local 沙箱驱动，不需要 Docker
# 先起 MySQL：mysql\mysql-8.4.9-winx64\bin\mysqld.exe --defaults-file=mysql\my.ini
# 或直接跑 dev-start.bat 一键起 MySQL + 后端 + 前端
# CODING_AGENT_SECRET_KEY 缺失时服务拒绝启动（推送凭据加密需要）
CODING_AGENT_SECRET_KEY=dev-secret-do-not-use-in-prod \
MIMO_API_KEY=sk-xxx CODING_AGENT_DEFAULT_PROVIDER=mimo \
  mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments=--server.port=8280
```

**本机必须用 8280 端口。** Hyper-V / WSL2 会保留若干 TCP 端口区间，其中 `8054–8153` 把 8080 包住了——绑端口时报 "Address already in use"，但 `netstat` 看着是空的。查：

```bash
netsh interface ipv4 show excludedportrange protocol=tcp
```

容器部署走 8080（Linux 不受此限制）。

### 前端（Node 20+ / Vite）

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173，把 /api 代理到 8280
npm run typecheck    # vue-tsc --noEmit
npm run build        # 类型检查 + 构建
```

### 全栈（生产形态）

```bash
cp .env.example .env    # 填入模型 API key
docker compose up -d
```

默认账号 `admin` / `admin123`，由 `DataBootstrap` 在 `user` 表为空时播种。

## 架构

三层解耦，这是关键——正是因为这三条缝，才能把 MySQL 换成 H2、local 沙箱换成 Docker 而不动 agent 逻辑。

```
frontend/  Vue3 + Vite
           │ SSE（POST /api/sessions/{id}/messages）
backend/   com.codingagent
           web/       HTTP、SSE、DTO、错误映射
           agent/     agent loop、工具集、上下文预算、审批策略
           model/     OpenAI 兼容的 LLM provider 抽象
           sandbox/   执行环境（LocalProcess | Docker）
           mcp/       MCP stdio 客户端 + 工具适配
           skill/     技能按需加载
           storage/   MyBatis-Plus 实体/mapper → MySQL
```

### Agent 循环（`agent/AgentLoop.java`）

跑在 `boundedElastic` 上的**阻塞实现**，不是响应式的——工具执行是同步的，审批要等用户点击，套响应式会让控制流散进回调里没法读。

每一轮：构造系统提示 → 发给模型 → 收流式文本与工具调用 → 逐个走审批闸门再分发给 `Tool` → 结果回填 → 循环，直到模型不再要工具或预算耗尽。

`ToolSet` 把静态的 `ToolRegistry` 与会话级 MCP 工具合并。`ToolContext` 携带沙箱 + 预算 + 项目配置 + 技能表。

### 工具契约（`agent/Tool.java`）

每个工具自己声明 JSON Schema 和 `requiresApproval(args)`——**审批规则跟着工具走**，不是在循环里写 switch。工具返回 `ToolResult`（含失败）而不是抛异常：模型需要看到失败原因才能自我修正。

### 沙箱（`sandbox/`）

`SandboxDriver` 两个实现，由 `coding-agent.sandbox.driver` 切换：

- `local` —— 无隔离，命令以服务进程身份直接跑。**仅限开发。**
- `docker` —— 一会话一容器，工作区 bind mount 进去。生产用这个。

文件工具（`read_file` / `write_file` / `grep` / `glob`）跑在后端 JVM 里读宿主机文件系统，而 `shell` 跑在沙箱内。由于工作区是 bind mount 到 `/workspace` 的，`SandboxSession.workspacePath()` 对两个驱动都返回**宿主机**路径——文件工具和 shell 必须看到同一个目录。

### 工作区初始化（`web/WorkspaceInitializer.java`）

在**建会话时**执行，不是第一次发消息时（clone 延迟会把 SSE 流卡死）。

- `GIT_URL` → `git clone`
- `LOCAL_PATH` 且是 git 仓库 → 从该路径 clone
- `LOCAL_PATH` 非 git → 复制目录树 + `git init` + 首次提交

然后写入本地 `user.name` / `user.email`、关掉 `core.autocrlf`，最后建会话分支。

**必须先初始化、再插会话记录。** 顺序反了的话，clone 失败会留下一条没有代码的孤儿会话。

### 模型层（`model/`）

只有一个实现 `OpenAiCompatibleProvider`，覆盖 DeepSeek、智谱 GLM、小米 MiMo、以及自部署的 vLLM / SGLang。provider 配置成 `base-url` + `api-key` + `model` 三元组。`ModelRouter` 负责按任务类型覆盖（配置结构已就位，MVP 实际只用一个模型）。

### Git 与发布（`git/`）

「支线仓库」= **同一远程仓库上的发布分支**。`GitPublishService.publish()` 做的事是
`git push <remote> <会话分支>:<发布分支>`，先 fetch 确认远端状态再推。

- **agent 永不自动 push**——发布只由人在界面点按钮触发。发布是对外动作。
- **非快进时明确拒绝并说明原因**，不偷偷 force 覆盖。
- `GitCredentials`（`AutoCloseable`）负责凭据注入，两条实现：HTTPS token、SSH 私钥。
- `GitInfoService` 供界面展示分支、最近提交、待发布提交数。

## 容易破坏的不变量

**`reasoning_content` 绝不能回传给模型。** MiMo 等推理模型把思考过程放在与正文分离的字段里。provider 发出 `ReasoningDelta`；`AgentLoop` 单独累积、只落库供界面回放。`ChatMessage` 刻意没有这个字段，`SessionService.toChatMessage()` 重建历史时也丢弃它。一旦塞进 `messages`，后续轮次会被上游拒收。

**MCP 工具带命名空间** `mcp__{server}__{tool}`，避免与内置工具或彼此撞名。`ToolSet` 在冲突时让内置工具优先。MCP 工具一律要求人工确认——外部进程的能力无法静态判断。

**工具输出截断是承重墙，不是装饰。** 没有 `shellOutputMaxLines` / `shellOutputMaxChars` / `fileReadMaxLines`，一次 `npm test` 的输出就能吃掉几万 token。截断逻辑在 `AgentLimits`，由各工具强制执行。

**search/replace 是主编辑格式**（`write_file` 的 `edits`）。`old_text` 必须唯一匹配；任何一处失败则整体不写盘，而不是应用部分编辑。整文件 `content` 是降级路径。

**SSE 解析必须同时认两种形态**：带 `data: ` 前缀的原始行，以及裸 JSON payload。Spring WebFlux 的 `bodyToFlux(String.class)` 对 `text/event-stream` 会把前缀剥掉。只认带前缀的那种会**静默得到零个事件**——模型调用"成功"但什么都没返回，且没有任何报错。

**推送凭据绝不落明文。** 三条约束缺一不可：

- 入库必须走 `SecretCipher`（AES-256-GCM，密钥 `CODING_AGENT_SECRET_KEY`）。密钥缺失时**拒绝启动**，不降级成明文。
- 注入 git 时**不进命令行**（`ps` 能看到进程参数）。
- 注入 git 时**不进 git config**（会持久化到仓库，clone 出去就泄了）。

具体手段：HTTPS token 走 `GIT_ASKPASS` + 环境变量（脚本本身是静态安全的，不含密钥）；SSH 私钥写临时文件 0600 + `GIT_SSH_COMMAND -i`，`close()` 时删掉。接口只回掩码，不提供查看原值——那会让加密形同虚设。

## 测试注意

- 测试会真的调 `git`、真的起进程。`McpClientTest` 把一个临时 Node MCP 服务器写到临时文件里跑（`@EnabledIf("nodeAvailable")`）。
- `WorkspaceInitializerTest` 会造临时 git 仓库。**不要往"非 git"的夹具里塞假的 `.git` 目录**——初始化器看到 `.git` 就会走 clone 分支。
- 测试方法名大量使用中文，**方法名不能含空格**（Java 标识符限制）。
- Windows 上 Git Bash 发中文命令行参数走 GBK，`curl -d '中文'` 会报服务端 `Invalid UTF-8`。把 JSON body 写到文件里，用 `--data-binary @file`。
- **源码里不要出现裸控制字符**（如 U+001F 字段分隔符）。它们会破坏工具调用的 JSON 序列化，表现为 Write/Bash 报「缺少必填参数」——报错完全指向不到真正原因。写 Java 字符串分隔符用转义序列 `"\u001f"`，不要写成实际字符。

## 存储

生产用 MySQL；本机开发也用 MySQL——项目内便携版装在 `mysql/` 目录（8.4.9 winx64 zip），不注册系统服务，数据落在 `mysql/data/`。`application-dev.yml` 指向 `127.0.0.1:3306/coding_agent`（`coding_agent` / `coding_agent`）。启动顺序：先 `mysqld`，再后端。

`schema.sql` 里是 `CREATE TABLE IF NOT EXISTS` 加尾部一节 `ALTER`——`IF NOT EXISTS` **不会给已存在的表加列**，升级靠尾部 ALTER 被 `sql.init.continue-on-error` 容忍。

`user` 是 SQL 保留字，`User` 实体必须写成 `` @TableName("`user`") ``，否则生成的查询语法错误。
