import type {
  AgentConfig, CheckResult, DiffResponse, GitInfo, McpServer, Message, Project,
  PublishConfig, PublishResult, Session, Skill, User, AgentEvent, SettingsView,
} from './types'
import { auth, clearSession } from './store'

/**
 * 后端 API 封装。
 *
 * <p>SSE 用 fetch + ReadableStream 手写，而不是 EventSource：
 * EventSource 只支持 GET，而发消息是 POST；且它拿不到 Authorization 头。
 */

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

function headers(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(extra ?? {}),
  }
  if (auth.token) {
    h['Authorization'] = `Bearer ${auth.token}`
  }
  return h
}

/** 401 时清掉登录态，交给路由守卫重定向 */
function handleUnauthorized(status: number) {
  if (status === 401) {
    clearSession()
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { ...init, headers: headers(init?.headers as Record<string, string>) })
  if (!res.ok) {
    let message = `请求失败 (${res.status})`
    try {
      const body = await res.json()
      message = body.error ?? message
    } catch {
      // 非 JSON 响应
    }
    handleUnauthorized(res.status)
    throw new ApiError(res.status, message)
  }
  // 空 body 不能走 res.json()：后端 void 接口回的是 200 + 空体（而不是 204），
  // JSON.parse('') 直接抛 SyntaxError，会让「删除」这类操作看起来毫无反应。
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  // ---------- 认证 ----------
  async login(username: string, password: string) {
    return request<{ token: string; user: User; loginAt: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },

  me: () => request<User>('/api/auth/me'),

  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

  changePassword(oldPassword: string, newPassword: string) {
    return request<void>('/api/auth/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword }),
    })
  },

  // ---------- 项目 ----------
  listProjects: () => request<Project[]>('/api/projects'),

  getProject: (id: number) => request<Project>(`/api/projects/${id}`),

  createProject(input: {
    name: string
    repoType: string
    repoUrl?: string
    localPath?: string
    sandboxImage?: string
    modelOverride?: string
    agent?: Partial<AgentConfig>
  }) {
    return request<Project>('/api/projects', { method: 'POST', body: JSON.stringify(input) })
  },

  updateProject(id: number, input: {
    name?: string
    repoType?: string
    repoUrl?: string
    localPath?: string
    sandboxImage?: string
    modelOverride?: string
    agent?: Partial<AgentConfig>
  }) {
    return request<Project>(`/api/projects/${id}`, { method: 'PUT', body: JSON.stringify(input) })
  },

  deleteProject: (id: number) => request<void>(`/api/projects/${id}`, { method: 'DELETE' }),

  // ---------- 会话 ----------
  listSessions: (projectId?: number) =>
    request<Session[]>(projectId ? `/api/sessions?projectId=${projectId}` : '/api/sessions'),

  createSession(input: { projectId: number; title?: string; approvalPolicy?: string }) {
    return request<Session>('/api/sessions', { method: 'POST', body: JSON.stringify(input) })
  },

  getSession: (id: string) => request<Session>(`/api/sessions/${id}`),

  listMessages: (id: string) => request<Message[]>(`/api/sessions/${id}/messages`),

  getDiff: (id: string) => request<DiffResponse>(`/api/sessions/${id}/diff`),

  purgeSession: (id: string) => request<Session>(`/api/sessions/${id}/purge`, { method: 'POST' }),

  decideApproval(sessionId: string, approvalId: string, approved: boolean) {
    return request<{ resolved: boolean }>(`/api/sessions/${sessionId}/approval`, {
      method: 'POST',
      body: JSON.stringify({ approvalId, approved }),
    })
  },

  /** patch 的下载地址（浏览器直接带 token 不便，走 fetch 拿文本再触发下载） */
  async downloadPatch(id: string, filename: string) {
    const res = await fetch(`/api/sessions/${id}/patch`, { headers: headers() })
    if (!res.ok) {
      handleUnauthorized(res.status)
      throw new ApiError(res.status, 'patch 生成失败')
    }
    const text = await res.text()
    const blob = new Blob([text], { type: 'text/x-patch;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  },

  // ---------- 设置 ----------
  getSettings: () => request<SettingsView>('/api/settings'),

  // ---------- 项目扩展：MCP ----------
  listMcpServers: (projectId: number) =>
    request<McpServer[]>(`/api/projects/${projectId}/mcp-servers`),

  createMcpServer(projectId: number, input: Partial<McpServer>) {
    return request<McpServer>(`/api/projects/${projectId}/mcp-servers`, {
      method: 'POST', body: JSON.stringify(input),
    })
  },

  updateMcpServer(projectId: number, id: number, input: Partial<McpServer>) {
    return request<McpServer>(`/api/projects/${projectId}/mcp-servers/${id}`, {
      method: 'PUT', body: JSON.stringify(input),
    })
  },

  deleteMcpServer: (projectId: number, id: number) =>
    request<void>(`/api/projects/${projectId}/mcp-servers/${id}`, { method: 'DELETE' }),

  checkMcpServer: (projectId: number, id: number) =>
    request<CheckResult>(`/api/projects/${projectId}/mcp-servers/${id}/check`, { method: 'POST' }),

  // ---------- 项目扩展：Skills ----------
  listSkills: (projectId: number) => request<Skill[]>(`/api/projects/${projectId}/skills`),

  createSkill(projectId: number, input: Partial<Skill>) {
    return request<Skill>(`/api/projects/${projectId}/skills`, {
      method: 'POST', body: JSON.stringify(input),
    })
  },

  updateSkill(projectId: number, id: number, input: Partial<Skill>) {
    return request<Skill>(`/api/projects/${projectId}/skills/${id}`, {
      method: 'PUT', body: JSON.stringify(input),
    })
  },

  deleteSkill: (projectId: number, id: number) =>
    request<void>(`/api/projects/${projectId}/skills/${id}`, { method: 'DELETE' }),

  // ---------- Git 与发布 ----------
  getPublishConfig: (projectId: number) =>
    request<PublishConfig>(`/api/projects/${projectId}/publish/config`),

  savePublishConfig(projectId: number, input: {
    publishBranch?: string
    publishRemote?: string
    credentialType?: string
    username?: string
    /** 省略表示不改；空串表示清除 */
    credential?: string
  }) {
    return request<PublishConfig>(`/api/projects/${projectId}/publish/config`, {
      method: 'PUT', body: JSON.stringify(input),
    })
  },

  publish(projectId: number, sessionId: string) {
    return request<PublishResult>(`/api/projects/${projectId}/publish`, {
      method: 'POST', body: JSON.stringify({ sessionId }),
    })
  },

  getGitInfo: (projectId: number, sessionId: string) =>
    request<GitInfo>(`/api/projects/${projectId}/publish/git-info?sessionId=${sessionId}`),
}

/**
 * 发送消息并订阅执行过程。
 *
 * @returns 用于中止的函数
 */
export function sendMessage(
  sessionId: string,
  content: string,
  onEvent: (ev: AgentEvent) => void,
  onError: (e: Error) => void,
  onDone: () => void,
): () => void {
  const controller = new AbortController()

  ;(async () => {
    try {
      const res = await fetch(`/api/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: headers({ Accept: 'text/event-stream' }),
        body: JSON.stringify({ content }),
        signal: controller.signal,
      })

      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        handleUnauthorized(res.status)
        throw new ApiError(res.status, body.error ?? `请求失败 (${res.status})`)
      }
      if (!res.body) {
        throw new Error('响应无内容流')
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        let sep = buffer.indexOf('\n\n')
        while (sep >= 0) {
          const frame = buffer.slice(0, sep)
          buffer = buffer.slice(sep + 2)
          handleFrame(frame, onEvent)
          sep = buffer.indexOf('\n\n')
        }
      }
      onDone()
    } catch (e) {
      if (controller.signal.aborted) return
      onError(e instanceof Error ? e : new Error(String(e)))
    }
  })()

  return () => controller.abort()
}

function handleFrame(frame: string, onEvent: (ev: AgentEvent) => void) {
  for (const line of frame.split('\n')) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice(5).trim()
    if (!payload) continue
    try {
      const envelope = JSON.parse(payload)
      if (envelope.type && envelope.data !== null && envelope.data !== undefined) {
        onEvent({ type: envelope.type, ...envelope.data } as AgentEvent)
      }
    } catch {
      // 忽略无法解析的帧，避免单帧异常打断整个流
    }
  }
}
