-- Coding Agent 数据库结构
--
-- 刻意不写 CREATE DATABASE / USE：建库由部署方负责（docker-compose 的
-- MYSQL_DATABASE 变量、或手工 CREATE DATABASE），这样这段 DDL 在
-- MySQL 与开发用的 H2 (MODE=MySQL) 上都能跑。
--
-- 引擎选 InnoDB + utf8mb4：消息正文可能含中文与 emoji

-- ---------- 用户 ----------
CREATE TABLE IF NOT EXISTS `user` (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name  VARCHAR(128) NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'MEMBER',   -- ADMIN / MEMBER
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 项目 ----------
CREATE TABLE IF NOT EXISTS project (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  owner_id       BIGINT       NOT NULL,
  name           VARCHAR(128) NOT NULL,
  -- repo_type: GIT_URL / LOCAL_PATH
  repo_type      VARCHAR(16)  NOT NULL,
  repo_url       VARCHAR(1024) NULL,
  local_path     VARCHAR(1024) NULL,
  -- 自定义沙箱镜像，为空则用全局默认镜像
  sandbox_image  VARCHAR(255) NULL,
  -- 项目级模型覆盖，形如 providerId/modelName，为空则用全局默认
  model_override VARCHAR(128) NULL,

  -- ===== 以下为 agent 行为配置 =====
  -- 项目专属指令，追加到内置系统提示之后（技术栈、代码约定、目录结构等）
  agent_instructions MEDIUMTEXT NULL,
  -- 启用的工具名，逗号分隔；为空表示全部启用
  enabled_tools  VARCHAR(512) NULL,
  -- 项目默认审批策略，新会话继承；为空则用全局默认
  approval_policy VARCHAR(24) NULL,
  -- 执行预算覆盖；为空则用全局配置
  max_turns      INT          NULL,
  token_budget   BIGINT       NULL,
  -- agent 忽略的路径 glob 模式，逗号或换行分隔
  exclude_paths  TEXT         NULL,

  -- ===== 发布到支线仓库（同远程的发布分支）=====
  -- 目标分支名，如 release/2026-10、publish
  publish_branch    VARCHAR(255) NULL,
  -- 远程名，默认 origin
  publish_remote    VARCHAR(64)  NULL,
  -- 推送凭据类型：TOKEN / SSH_KEY
  publish_credential_type VARCHAR(16) NULL,
  -- HTTPS 用户名（token 模式用，通常 git 或 oauth2）
  publish_username  VARCHAR(128) NULL,
  -- 推送凭据，AES-GCM 加密后以 base64 存储。明文绝不入库。
  publish_credential TEXT        NULL,

  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_owner (owner_id),
  CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 会话 ----------
CREATE TABLE IF NOT EXISTS session (
  id              VARCHAR(64)  NOT NULL,
  project_id      BIGINT       NOT NULL,
  user_id         BIGINT       NOT NULL,
  title           VARCHAR(255) NULL,
  -- workspace 在宿主机上的绝对路径
  workspace_path  VARCHAR(1024) NOT NULL,
  -- 会话专属分支名
  branch_name     VARCHAR(255) NULL,
  -- RUNNING / COMPLETED / FAILED / ARCHIVED
  status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
  -- AUTO / ASK_ON_WRITE / ALWAYS_ASK
  approval_policy VARCHAR(24)  NOT NULL DEFAULT 'ASK_ON_WRITE',
  -- 工作区是否已回收；回收后只能回看历史
  workspace_purged TINYINT(1)  NOT NULL DEFAULT 0,
  purged_at       DATETIME     NULL,
  prompt_tokens   BIGINT       NOT NULL DEFAULT 0,
  completion_tokens BIGINT     NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session_project (project_id),
  KEY idx_session_user (user_id),
  KEY idx_session_purged (workspace_purged, updated_at),
  CONSTRAINT fk_session_project FOREIGN KEY (project_id) REFERENCES project (id),
  CONSTRAINT fk_session_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 消息 ----------
-- 一条记录对应一次 user 输入 / assistant 输出 / 工具结果
CREATE TABLE IF NOT EXISTS message (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  session_id     VARCHAR(64)  NOT NULL,
  -- USER / ASSISTANT / TOOL / SYSTEM
  role           VARCHAR(16)  NOT NULL,
  content        MEDIUMTEXT   NULL,
  -- 推理模型的思考过程，仅供界面回看。
  -- ⚠ 绝不能回传给模型：MiMo 等推理模型明确要求 messages 里不得含 reasoning_content
  reasoning      MEDIUMTEXT   NULL,
  -- assistant 发起的工具调用，JSON 数组
  tool_calls     JSON         NULL,
  tool_call_id   VARCHAR(64)  NULL,
  tool_name      VARCHAR(64)  NULL,
  -- 工具结果是否被截断
  truncated      TINYINT(1)   NOT NULL DEFAULT 0,
  -- 会话内全局轮次序号（1 起，永不复用）。旧数据为 NULL：视为第 0 轮，回滚永不触及
  turn           INT          NULL,
  -- 是否已被回滚（不进模型历史，仅界面可见）。整轮同进同出
  rolled_back    TINYINT(1)   NOT NULL DEFAULT 0,
  prompt_tokens  INT          NOT NULL DEFAULT 0,
  completion_tokens INT       NOT NULL DEFAULT 0,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_message_session (session_id, id),
  KEY idx_message_session_turn (session_id, turn),
  CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 轮次检查点（rewind 的回滚点） ----------
-- 每个 agent 轮次结束时（AssistantCommitted 落库后）快照一次代码状态。
-- 回滚目标 = 某个检查点；turn=0 为会话初始状态（首条消息发送前建立）。
CREATE TABLE IF NOT EXISTS turn_checkpoint (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  session_id    VARCHAR(64)  NOT NULL,
  turn          INT          NOT NULL,
  -- 该轮结束时工作区 HEAD（含 agent 用 git_commit 打的提交）
  head_sha      VARCHAR(64)  NOT NULL,
  -- 快照 commit 的 sha，其树 = 该轮结束时的工作区状态（含未提交改动）。
  -- 存 commit 而不是裸 tree：只有被 commit 引用的 tree 才不会被 gc 掉
  snapshot_sha  VARCHAR(64)  NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_checkpoint_session_turn (session_id, turn),
  CONSTRAINT fk_checkpoint_session FOREIGN KEY (session_id) REFERENCES session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 回滚/恢复操作日志 ----------
-- 每次回滚或恢复记一行：记下操作前的状态备份与翻转的轮次集合，
-- 使「恢复」= 精确撤销某一步成为可能。数据永不删除。
CREATE TABLE IF NOT EXISTS session_rewind (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  session_id          VARCHAR(64)  NOT NULL,
  user_id             BIGINT       NULL,
  -- REWIND=回滚到某轮 / RECOVER=撤销某一步（恢复）
  kind                VARCHAR(16)  NOT NULL,
  -- 操作前后的时间线尖端轮次（from_turn → to_turn）
  from_turn           INT          NOT NULL,
  to_turn             INT          NOT NULL,
  -- 被本次撤销的操作 id（kind=RECOVER 时有值）
  undone_op_id        BIGINT       NULL,
  -- 本次翻转为「已回滚」的轮次，JSON int 数组，如 [6,7]；空数组写 "[]"
  rolled_turns        TEXT         NULL,
  -- 本次翻转为「活跃」的轮次，JSON int 数组
  unrolled_turns      TEXT         NULL,
  -- 操作前状态备份：备份 ref 名、快照 commit sha、其时 HEAD sha
  backup_ref          VARCHAR(255) NULL,
  backup_snapshot_sha VARCHAR(64)  NULL,
  backup_head_sha     VARCHAR(64)  NULL,
  -- PENDING=执行中 / APPLIED=已生效（可撤销）/ RESTORED=已被撤销 / FAILED=执行失败
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_rewind_session (session_id, id),
  CONSTRAINT fk_rewind_session FOREIGN KEY (session_id) REFERENCES session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 审计日志 ----------
-- agent 会跑 shell，出事要能追溯：谁、何时、让它做了什么
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  session_id  VARCHAR(64)  NULL,
  user_id     BIGINT       NULL,
  -- TOOL_CALL / APPROVAL / SESSION_LIFECYCLE
  action      VARCHAR(32)  NOT NULL,
  tool_name   VARCHAR(64)  NULL,
  detail      MEDIUMTEXT   NULL,
  -- 是否被人工放行
  approved    TINYINT(1)   NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_session (session_id, created_at),
  KEY idx_audit_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 项目级 MCP 服务器 ----------
-- 每个项目可以挂自己的 MCP 工具服务器
CREATE TABLE IF NOT EXISTS project_mcp_server (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT       NOT NULL,
  -- 服务器标识，用于工具命名空间 mcp__{name}__{tool}，项目内唯一
  name        VARCHAR(64)  NOT NULL,
  -- STDIO / SSE / HTTP
  transport   VARCHAR(16)  NOT NULL DEFAULT 'STDIO',
  -- STDIO：启动命令与参数。args 为 JSON 数组，env 为 KEY=VALUE 每行一条
  command     VARCHAR(512) NULL,
  args        TEXT         NULL,
  env         TEXT         NULL,
  -- SSE / HTTP：端点地址
  url         VARCHAR(1024) NULL,
  enabled     TINYINT(1)   NOT NULL DEFAULT 1,
  -- 最近一次连通性探测的结果，便于在 UI 上直接看到是否可用
  last_check_at   DATETIME NULL,
  last_check_ok   TINYINT(1) NULL,
  last_check_msg  VARCHAR(512) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mcp_project_name (project_id, name),
  KEY idx_mcp_project (project_id),
  CONSTRAINT fk_mcp_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 项目级 Skills ----------
-- 技能 = 一组可复用的指令包，agent 按需加载
CREATE TABLE IF NOT EXISTS project_skill (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT       NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  description VARCHAR(512) NULL,
  -- markdown 指令正文
  content     MEDIUMTEXT   NOT NULL,
  enabled     TINYINT(1)   NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_project_name (project_id, name),
  KEY idx_skill_project (project_id),
  CONSTRAINT fk_skill_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 结构升级 ----------
-- CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，这里补幂等 ALTER。
-- 首次建库时这些会报「列已存在」，由 sql.init.continue-on-error 忽略。
-- 正式部署若用全新库，前面的 CREATE TABLE 已包含这些列。
ALTER TABLE message ADD COLUMN reasoning MEDIUMTEXT NULL;
ALTER TABLE project ADD COLUMN publish_branch VARCHAR(255) NULL;
ALTER TABLE project ADD COLUMN publish_remote VARCHAR(64) NULL;
ALTER TABLE project ADD COLUMN publish_credential_type VARCHAR(16) NULL;
ALTER TABLE project ADD COLUMN publish_username VARCHAR(128) NULL;
ALTER TABLE project ADD COLUMN publish_credential TEXT NULL;
-- rewind（回滚会话+代码）
ALTER TABLE message ADD COLUMN turn INT NULL;
ALTER TABLE message ADD COLUMN rolled_back TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE message ADD INDEX idx_message_session_turn (session_id, turn);
