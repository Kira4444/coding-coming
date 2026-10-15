<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, sendMessage } from '../api'
import type { AgentEvent, DiffResponse, Message, Session } from '../types'
import { t } from '../i18n'
import ToolCard from '../components/ToolCard.vue'
import ApprovalModal from '../components/ApprovalModal.vue'
import DiffView from '../components/DiffView.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()

const session = ref<Session | null>(null)
const history = ref<Message[]>([])
const loading = ref(true)
const errorMsg = ref('')

const tab = ref<'chat' | 'diff'>('chat')
const diff = ref<DiffResponse | null>(null)
const diffLoading = ref(false)
const diffError = ref('')

/** 流式对话中的临时视图：消息与工具卡片混排 */
interface FeedItem {
  kind: 'user' | 'tool' | 'approval' | 'error'
  text?: string
  toolName?: string
  toolArgs?: string
  toolCallId?: string
  status?: 'pending' | 'running' | 'ok' | 'err'
  output?: string
  truncated?: boolean
  elapsedMs?: number
  approvalId?: string
  preview?: string
  reason?: string
}

const feed = ref<FeedItem[]>([])
const streamingText = ref('')
/** 推理模型的思考过程，折叠展示 */
const streamingReasoning = ref('')
const reasoningOpen = ref(false)
/** 历史消息里展开的思考块 id */
const historyReasoningOpen = ref<Set<number>>(new Set())
const isRunning = ref(false)

const input = ref('')
const scrollEl = ref<HTMLElement | null>(null)

watch(() => props.id, () => void load(), { immediate: true })

onMounted(() => {
  // 切到改动页时才拉 diff，避免每次进会话都跑一次 git
  watch(tab, (v) => {
    if (v === 'diff') void loadDiff()
  })
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  feed.value = []
  streamingText.value = ''
  try {
    session.value = await api.getSession(props.id)
    history.value = await api.listMessages(props.id)
    await scrollBottom()
  } catch (e) {
    errorMsg.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function loadDiff() {
  diffLoading.value = true
  diffError.value = ''
  try {
    diff.value = await api.getDiff(props.id)
  } catch (e) {
    diffError.value = (e as Error).message
  } finally {
    diffLoading.value = false
  }
}

function submit() {
  const content = input.value.trim()
  if (!content || isRunning.value || !session.value || session.value.workspacePurged) return

  input.value = ''
  feed.value.push({ kind: 'user', text: content })
  streamingText.value = ''
  streamingReasoning.value = ''
  reasoningOpen.value = true
  isRunning.value = true
  errorMsg.value = ''
  void scrollBottom()

  sendMessage(
    props.id,
    content,
    onAgentEvent,
    (e) => {
      isRunning.value = false
      errorMsg.value = e.message
    },
    () => {
      isRunning.value = false
      streamingText.value = ''
      // 思考过程保留到下一轮开始才清空，便于用户回看刚才那步为什么这么走
      void refresh()
    },
  )
}

function onAgentEvent(ev: AgentEvent) {
  switch (ev.type) {
    case 'reasoning_delta':
      // 只展示，不进对话历史
      streamingReasoning.value += ev.text
      void scrollBottom()
      break

    case 'text_delta':
      streamingText.value += ev.text
      void scrollBottom()
      break

    case 'tool_call':
      feed.value.push({
        kind: 'tool',
        toolName: ev.toolCall.name,
        toolArgs: ev.toolCall.arguments,
        toolCallId: ev.toolCall.id,
        status: 'running',
      })
      void scrollBottom()
      break

    case 'approval_required':
      feed.value.push({
        kind: 'approval',
        approvalId: ev.approvalId,
        preview: ev.preview,
        reason: ev.reason,
        toolName: ev.toolCall.name,
        toolArgs: ev.toolCall.arguments,
        toolCallId: ev.toolCall.id,
      })
      void scrollBottom()
      break

    case 'approval_resolved': {
      const idx = feed.value.findIndex(
        (f) => f.kind === 'approval' && f.approvalId === ev.approvalId,
      )
      if (idx >= 0) {
        const item = feed.value[idx]
        feed.value[idx] = {
          kind: 'tool',
          toolName: item.toolName,
          toolArgs: item.toolArgs,
          toolCallId: item.toolCallId,
          status: ev.approved ? 'running' : 'err',
          output: ev.approved ? '' : '用户拒绝了这次操作',
        }
      }
      break
    }

    case 'tool_completed': {
      const idx = feed.value.findIndex(
        (f) => f.kind === 'tool' && f.toolCallId === ev.toolCall.id && f.status === 'running',
      )
      if (idx >= 0) {
        feed.value[idx] = {
          ...feed.value[idx],
          status: ev.result.ok ? 'ok' : 'err',
          output: ev.result.content,
          truncated: ev.result.truncated,
          elapsedMs: ev.elapsedMs,
        }
      }
      void scrollBottom()
      break
    }

    case 'failed':
      isRunning.value = false
      feed.value.push({ kind: 'error', text: ev.message })
      void scrollBottom()
      break

    default:
      break
  }
}

async function decideApproval(approvalId: string, approved: boolean) {
  try {
    await api.decideApproval(props.id, approvalId, approved)
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
}

async function refresh() {
  try {
    session.value = await api.getSession(props.id)
    history.value = await api.listMessages(props.id)
  } catch {
    // 刷新失败不影响当前展示
  }
}

async function downloadPatch() {
  try {
    await api.downloadPatch(props.id, `${props.id}.patch`)
  } catch (e) {
    diffError.value = (e as Error).message
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

async function scrollBottom() {
  await nextTick()
  scrollEl.value?.scrollTo({ top: scrollEl.value.scrollHeight })
}

function toggleHistoryReasoning(id: number) {
  const next = new Set(historyReasoningOpen.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  historyReasoningOpen.value = next
}

const usage = computed(() => ({
  prompt: session.value?.promptTokens ?? 0,
  completion: session.value?.completionTokens ?? 0,
}))
</script>

<template>
  <div class="chat-page">
    <header class="chat-header">
      <div class="chat-head-left">
        <button class="ghost" @click="router.push({ name: 'sessions' })">←</button>
        <div>
          <div class="chat-title">{{ session?.title ?? t('loading') }}</div>
          <div class="chat-sub">
            {{ t('branch') }}: {{ session?.branchName ?? '-' }}
            · {{ t('approvalPolicy') }}: {{ t(session?.approvalPolicy === 'AUTO' ? 'policyAuto' : session?.approvalPolicy === 'ALWAYS_ASK' ? 'policyAlwaysAsk' : 'policyAskOnWrite') }}
          </div>
        </div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'chat' }" @click="tab = 'chat'">
          {{ t('chat') }}
        </button>
        <button class="tab" :class="{ active: tab === 'diff' }" @click="tab = 'diff'">
          {{ t('diff') }}
        </button>
      </div>

      <div class="usage-pill">
        <span>{{ t('promptTokens') }} {{ usage.prompt.toLocaleString() }}</span>
        <span>{{ t('completionTokens') }} {{ usage.completion.toLocaleString() }}</span>
      </div>
    </header>

    <!-- ---------- 对话 ---------- -->
    <template v-if="tab === 'chat'">
      <div ref="scrollEl" class="chat-scroll">
        <div v-if="loading" class="loading">{{ t('loading') }}</div>
        <template v-else>
          <div v-if="errorMsg" class="notice error">{{ errorMsg }}</div>
          <div v-if="session?.workspacePurged" class="notice info">
            {{ t('workspacePurged') }}
          </div>

          <div v-for="m in history" :key="'h' + m.id" class="msg" :class="m.role.toLowerCase()">
            <div class="msg-role">{{ m.role }}</div>

            <!-- 历史里的思考过程：默认折叠，点开可回看当时怎么想的 -->
            <div v-if="m.reasoning" class="reasoning">
              <div class="reasoning-head" @click="toggleHistoryReasoning(m.id)">
                <span class="reasoning-label">{{ t('thinking') }}</span>
                <span class="chevron">{{ historyReasoningOpen.has(m.id) ? '▾' : '▸' }}</span>
              </div>
              <div v-if="historyReasoningOpen.has(m.id)" class="reasoning-body">{{ m.reasoning }}</div>
            </div>

            <div v-if="m.content" class="msg-body">{{ m.content }}</div>
          </div>

          <template v-for="(item, i) in feed" :key="i">
            <div v-if="item.kind === 'user'" class="msg user">
              <div class="msg-role">USER</div>
              <div class="msg-body">{{ item.text }}</div>
            </div>

            <div v-else-if="item.kind === 'error'" class="notice error">{{ item.text }}</div>

            <ApprovalModal
              v-else-if="item.kind === 'approval' && item.approvalId"
              :preview="item.preview ?? ''"
              :reason="item.reason ?? ''"
              @approve="decideApproval(item.approvalId!, true)"
              @reject="decideApproval(item.approvalId!, false)"
            />

            <ToolCard
              v-else-if="item.kind === 'tool'"
              :tool-call="{ id: item.toolCallId ?? '', name: item.toolName ?? '', arguments: item.toolArgs ?? '{}' }"
              :status="item.status ?? 'pending'"
              :output="item.output"
              :truncated="item.truncated"
              :elapsed-ms="item.elapsedMs"
            />
          </template>

          <!-- 推理模型的思考过程：折叠展示，不进历史 -->
          <div v-if="streamingReasoning" class="reasoning">
            <div class="reasoning-head" @click="reasoningOpen = !reasoningOpen">
              <span class="reasoning-label">{{ t('thinking') }}</span>
              <span class="chevron">{{ reasoningOpen ? '▾' : '▸' }}</span>
            </div>
            <div v-if="reasoningOpen" class="reasoning-body">{{ streamingReasoning }}</div>
          </div>

          <div v-if="streamingText" class="msg assistant">
            <div class="msg-role">ASSISTANT</div>
            <div class="msg-body">{{ streamingText }}<span class="cursor"></span></div>
          </div>

          <div v-if="isRunning && !streamingText && feed.length === 0" class="msg assistant">
            <div class="msg-body dim">{{ t('thinking') }}</div>
          </div>
        </template>
      </div>

      <div class="composer">
        <textarea
          v-model="input"
          :placeholder="t('inputPlaceholder')"
          :disabled="isRunning || session?.workspacePurged"
          @keydown="onKeydown"
        ></textarea>
        <div class="composer-row">
          <span class="composer-hint">
            Enter 发送 · Shift+Enter 换行
            <template v-if="isRunning"> · {{ t('running') }}</template>
          </span>
          <button class="primary" :disabled="!input.trim() || isRunning" @click="submit">
            {{ t('send') }}
          </button>
        </div>
      </div>
    </template>

    <!-- ---------- 改动 ---------- -->
    <div v-else class="diff-pane">
      <div class="diff-bar">
        <button @click="loadDiff">↻ {{ t('refresh') }}</button>
        <button class="primary" :disabled="!diff || diff.files.length === 0" @click="downloadPatch">
          ↓ {{ t('downloadPatch') }}
        </button>
      </div>
      <DiffView :diff="diff" :loading="diffLoading" :error="diffError" />
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  min-height: 56px;
}
.chat-head-left {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  flex: 1;
}
.ghost {
  background: transparent;
  border-color: transparent;
  color: var(--text-dim);
}
.ghost:hover {
  background: var(--bg-input);
}
.chat-title {
  font-weight: 600;
  font-size: 14.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-sub {
  color: var(--text-faint);
  font-size: 11.5px;
}

.tabs {
  display: flex;
  gap: 3px;
  background: var(--bg-input);
  padding: 3px;
  border-radius: var(--radius-sm);
}
.tab {
  border: none;
  background: transparent;
  padding: 5px 13px;
  border-radius: 4px;
  font-size: 12.5px;
  color: var(--text-dim);
}
.tab:hover {
  color: var(--text);
}
.tab.active {
  background: var(--bg-elevated);
  color: var(--text);
}

.usage-pill {
  display: flex;
  gap: 11px;
  font-size: 11px;
  color: var(--text-faint);
  font-family: var(--mono);
  background: var(--bg-input);
  padding: 4px 10px;
  border-radius: 20px;
  flex-shrink: 0;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px 12px;
}
.loading {
  color: var(--text-faint);
}

.composer {
  border-top: 1px solid var(--border);
  padding: 13px 24px 16px;
  background: var(--bg-elevated);
}
.composer textarea {
  width: 100%;
  min-height: 66px;
  max-height: 210px;
}
.composer-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  gap: 12px;
}
.composer-hint {
  color: var(--text-faint);
  font-size: 11.5px;
}

.diff-pane {
  flex: 1;
  overflow-y: auto;
  padding: 18px 24px 32px;
}
.diff-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.msg {
  margin-bottom: 15px;
  max-width: 900px;
}
.msg-role {
  font-size: 10.5px;
  letter-spacing: 0.7px;
  text-transform: uppercase;
  color: var(--text-faint);
  margin-bottom: 5px;
}
.msg-body {
  border-radius: var(--radius);
  padding: 11px 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-body.dim {
  color: var(--text-dim);
}
.msg.user .msg-body {
  background: var(--accent-soft);
  border: 1px solid rgba(79, 140, 255, 0.22);
}
.msg.assistant .msg-body {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
}
.msg.tool .msg-body {
  background: var(--bg);
  border: 1px solid var(--border);
  font-family: var(--mono);
  font-size: 12.5px;
}

.cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  background: var(--accent);
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: blink 1s steps(2) infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}

.reasoning {
  max-width: 900px;
  margin-bottom: 12px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius);
  overflow: hidden;
}
.reasoning-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;
  user-select: none;
  background: var(--bg-elevated);
}
.reasoning-head:hover {
  background: var(--bg-input);
}
.reasoning-label {
  font-size: 11px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--text-faint);
}
.chevron {
  margin-left: auto;
  color: var(--text-faint);
  font-size: 11px;
}
.reasoning-body {
  padding: 10px 13px;
  color: var(--text-dim);
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 1px solid var(--border);
  background: var(--bg);
}
</style>
