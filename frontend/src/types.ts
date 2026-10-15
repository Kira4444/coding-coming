/** 与后端 DTO 对齐的类型定义 */

export interface User {
  id: number
  username: string
  displayName?: string | null
  role: 'ADMIN' | 'MEMBER'
}

/** 项目级 agent 行为配置 */
export interface AgentConfig {
  instructions?: string | null
  enabledTools: string[]
  approvalPolicy?: string | null
  maxTurns?: number | null
  tokenBudget?: number | null
  excludePaths: string[]
}

export interface Project {
  id: number
  name: string
  repoType: 'GIT_URL' | 'LOCAL_PATH'
  repoUrl?: string | null
  localPath?: string | null
  sandboxImage?: string | null
  modelOverride?: string | null
  agent: AgentConfig
}

/** 内置工具清单，用于工具开关的勾选列表 */
export const KNOWN_TOOLS = [
  { name: 'read_file', label: '读文件', desc: '读取工作区文件内容', risk: '只读' },
  { name: 'write_file', label: '写文件', desc: '修改或创建文件（search/replace）', risk: '写' },
  { name: 'glob', label: '查找文件', desc: '按 glob 模式找文件', risk: '只读' },
  { name: 'grep', label: '内容搜索', desc: '按正则搜索文件内容', risk: '只读' },
  { name: 'shell', label: '执行命令', desc: '跑测试、构建、安装依赖等', risk: '高危' },
  { name: 'git_commit', label: 'Git 提交', desc: '把改动提交到会话分支', risk: '写' },
] as const

export interface McpServer {
  id: number
  name: string
  transport: 'STDIO' | 'SSE' | 'HTTP'
  command?: string | null
  args?: string | null
  env?: string | null
  url?: string | null
  enabled: boolean
  lastCheckOk?: boolean | null
  lastCheckMsg?: string | null
  lastCheckAt?: string | null
}

export interface Skill {
  id: number
  name: string
  description?: string | null
  content: string
  enabled: boolean
}

export interface CheckResult {
  ok: boolean
  message: string
  at: string
}

export interface PublishConfig {
  publishBranch?: string | null
  publishRemote?: string | null
  credentialType?: 'TOKEN' | 'SSH_KEY' | null
  username?: string | null
  credentialMasked: string
  credentialConfigured: boolean
}

export interface CommitInfo {
  hash: string
  shortHash: string
  subject: string
  author: string
  date: string
}

export interface GitInfo {
  gitRepo: boolean
  currentBranch?: string | null
  remoteUrl?: string | null
  remotes: string[]
  recentCommits: CommitInfo[]
  commitsAhead: number
  message?: string | null
}

export interface PublishResult {
  ok: boolean
  message: string
  details: string
}

export interface Session {
  id: string
  projectId: number
  title: string
  status: string
  approvalPolicy: 'AUTO' | 'ASK_ON_WRITE' | 'ALWAYS_ASK'
  branchName?: string | null
  workspacePurged: boolean
  promptTokens: number
  completionTokens: number
}

export interface Message {
  id: number
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM'
  content?: string | null
  /** 推理模型的思考过程，仅供回看，不回传给模型 */
  reasoning?: string | null
  toolCalls?: string | null
  toolCallId?: string | null
  toolName?: string | null
  truncated?: boolean | null
}

export interface ToolCall {
  id: string
  name: string
  arguments: string
}

export interface FileDiff {
  path: string
  oldPath: string
  changeType: 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED'
  added: number
  removed: number
  patch: string
}

export interface DiffResponse {
  branch: string
  baseRef: string
  files: FileDiff[]
  addedLines: number
  removedLines: number
}

export interface ProviderView {
  id: string
  baseUrl: string
  model: string
  apiKeyMasked: string
  configured: boolean
}

export interface SettingsView {
  providers: ProviderView[]
  defaultProvider: string
  taskOverrides: Record<string, string>
  sandboxDriver: string
  sandboxImage: string
  sandboxNetwork: string
  sandboxCpu: number
  sandboxMemory: string
  maxConcurrentSessions: number
  maxTurns: number
  tokenBudget: number
}

/** SSE 事件负载，与后端 AgentEvent 对齐 */
export type AgentEvent =
  | { type: 'turn_started'; sessionId: string; turn: number }
  | { type: 'text_delta'; text: string }
  /** 推理模型的思考过程，仅展示，不进历史 */
  | { type: 'reasoning_delta'; text: string }
  | { type: 'tool_call'; toolCall: ToolCall }
  | { type: 'approval_required'; approvalId: string; toolCall: ToolCall; reason: string; preview: string }
  | { type: 'approval_resolved'; approvalId: string; approved: boolean }
  | { type: 'tool_completed'; toolCall: ToolCall; result: { ok: boolean; content: string; truncated: boolean }; elapsedMs: number }
  | { type: 'usage'; promptTokens: number; completionTokens: number; totalTokens: number }
  | { type: 'assistant_committed'; content: string; toolCalls?: ToolCall[] }
  | { type: 'turn_completed'; totalTurns: number }
  | { type: 'failed'; message: string }
