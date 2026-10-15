import { reactive, readonly } from 'vue'
import type { User } from './types'

/**
 * 全局状态。
 *
 * <p>只放「跨页面、且刷新后需要保留」的东西：登录态。
 * 其余数据留在各页面自己的 ref 里——过早上 Pinia 只会让读代码的人多绕一层。
 */
const TOKEN_KEY = 'coding-agent-token'
const USER_KEY = 'coding-agent-user'

interface AuthState {
  token: string | null
  user: User | null
  /** 首次进入时是否已向后端确认过身份 */
  checked: boolean
}

const state = reactive<AuthState>({
  token: localStorage.getItem(TOKEN_KEY),
  user: readUser(),
  checked: false,
})

function readUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as User) : null
  } catch {
    return null
  }
}

export const auth = readonly(state)

export function setSession(token: string, user: User) {
  state.token = token
  state.user = user
  state.checked = true
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function setUser(user: User) {
  state.user = user
  state.checked = true
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function markChecked() {
  state.checked = true
}

export function clearSession() {
  state.token = null
  state.user = null
  state.checked = true
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function isLoggedIn(): boolean {
  return !!state.token
}
