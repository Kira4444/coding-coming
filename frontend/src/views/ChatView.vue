<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, ApiError, sendMessage } from '../api'
import type { AgentEvent, DiffResponse, Message, RewindOp, Session } from '../types'
import { t, tf } from '../i18n'
import ToolCard from '../components/ToolCard.vue'
import ApprovalModal from '../components/ApprovalModal.vue'
import DiffView from '../components/DiffView.vue'
import RewindPanel from '../components/RewindPanel.vue'

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

// ---------- 会话回滚（rewind） ----------
const showRewind = ref(false)
/** 回滚 / 恢复成功后的 toast */
const rewindTip = ref('')
/** 操作日志，用于找「可恢复的操作」 */
const rewinds = ref<RewindOp[]>([])
const showRecover = ref(false)
const recoverBusy = ref(false)
const recoverError = ref('')

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
    await loadRewinds()
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
    await loadRewinds()
  } catch {
    // 刷新失败不影响当前展示
  }
}

/** 操作日志只决定「恢复」入口的显隐，拉不到不影响主流程 */
async function loadRewinds() {
  try {
    rewinds.value = await api.listRewinds(props.id)
  } catch {
    rewinds.value = []
  }
}

// ---------- 回滚 / 恢复 ----------

/** 当前活跃时间线尖端 = 未回滚消息的最大 turn（旧行为 null 不参与） */
const currentTipTurn = computed(() => {
  let max = 0
  for (const m of history.value) {
    if (!m.rolledBack && typeof m.turn === 'number' && m.turn > max) max = m.turn
  }
  return max
})

/** 可恢复的操作 = id 最大的 APPLIED 行（栈纪律：更晚的记录是 RESTORED / FAILED 时不挡路） */
const recoverableOp = computed(() => {
  const applied = rewinds.value.filter((o) => o.status === 'APPLIED')
  return applied.reduce<RewindOp | null>(
    (best, o) => (!best || o.opId > best.opId ? o : best),
    null,
  )
})

/** 第一条已回滚消息：分隔条插在它前面 */
const firstRolledId = computed(() => history.value.find((m) => m.rolledBack)?.id ?? null)

const recoverDisabled = computed(() => isRunning.value || !!session.value?.workspacePurged || recoverBusy.value)

function recoverDisabledTip(): string {
  if (session.value?.workspacePurged) return t('chat.rewind.tipPurged')
  return t('chat.rewind.tipRunning')
}

function turnLabel(turn: number | null | undefined): string {
  if (turn == null) return ''
  return turn === 0 ? t('chat.rewind.initialTitle') : tf('chat.rewind.turnLabel', { turn })
}

function rewindErrorText(e: unknown): string {
  const msg = (e as Error).message
  return e instanceof ApiError && e.status === 409 ? `${msg}（${t('chat.rewind.refreshHint')}）` : msg
}

/** 回滚 / 恢复成功后：清掉 diff 缓存重拉、重拉消息、弹 toast */
async function afterTimelineChange(toast: string) {
  diff.value = null
  if (tab.value === 'diff') void loadDiff()
  await refresh()
  rewindTip.value = toast
  setTimeout(() => {
    if (rewindTip.value === toast) rewindTip.value = ''
  }, 2800)
}

function onRewindDone(op: { kind: 'REWIND' | 'RECOVER'; toTurn: number }) {
  const toast =
    op.toTurn === 0
      ? t('chat.rewind.rewindDoneZero')
      : tf('chat.rewind.rewindDone', { turn: op.toTurn })
  void afterTimelineChange(toast)
}

/** 恢复对 X 的反转：恢复 X 标掉的轮次，X 之后新增的分歧轮次移出活跃集 */
function recoverCounts() {
  const op = recoverableOp.value
  if (!op) return { restore: 0, added: 0 }
  const restore = op.rolledTurns.length
  const diverged = new Set<number>()
  for (const m of history.value) {
    if (!m.rolledBack && typeof m.turn === 'number' && m.turn > op.fromTurn) diverged.add(m.turn)
  }
  return { restore, added: diverged.size + op.unrolledTurns.length }
}

const recoverConfirmText = computed(() => {
  if (!recoverableOp.value) return ''
  const { restore, added } = recoverCounts()
  return added > 0
    ? tf('chat.rewind.recoverConfirmDiverged', { turns: restore, new: added })
    : tf('chat.rewind.recoverConfirmSimple', { turns: restore })
})

async function openRecover() {
  // 打开确认框前重取操作日志：可恢复的永远是「id 最大的 APPLIED 行」，避免用陈旧缓存
  await loadRewinds()
  if (!recoverableOp.value) return
  recoverError.value = ''
  showRecover.value = true
}

async function doRecover() {
  const op = recoverableOp.value
  if (!op || recoverBusy.value) return
  recoverBusy.value = true
  recoverError.value = ''
  try {
    const res = await api.recoverRewind(props.id, op.opId, { expectedTipTurn: currentTipTurn.value })
    showRecover.value = false
    const toast =
      res.toTurn === 0
        ? t('chat.rewind.recoverDoneZero')
        : tf('chat.rewind.recoverDone', { turn: res.toTurn })
    void afterTimelineChange(toast)
  } catch (e) {
    // 失败留在弹窗里显示后端文案（409 提示刷新），关掉会让人以为没反应
    recoverError.value = rewindErrorText(e)
  } finally {
    recoverBusy.value = false
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

      <button
        class="rewind-entry"
        :disabled="isRunning || session?.workspacePurged"
        :title="isRunning ? t('chat.rewind.tipRunning') : session?.workspacePurged ? t('chat.rewind.tipPurged') : ''"
        @click="showRewind = true"
      >
        ⟲ {{ t('chat.rewind.button') }}
      </button>

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
          <div v-if="rewindTip" class="notice info">{{ rewindTip }}</div>
          <div v-if="session?.workspacePurged" class="notice info">
            {{ t('workspacePurged') }}
          </div>

          <!-- 全部行照 id 顺序渲染（含已回滚），已回滚区灰显可见而非凭空消失 -->
          <template v-for="m in history" :key="'h' + m.id">
            <div v-if="m.id === firstRolledId" class="rewind-divider">
              <span class="rewind-divider-text">───── {{ t('chat.rewind.rolledBackBanner') }} ──────</span>
              <button
                v-if="recoverableOp"
                class="danger"
                :disabled="recoverDisabled"
                :title="recoverDisabled ? recoverDisabledTip() : ''"
                @click="openRecover"
              >
                {{ t('chat.rewind.recoverAction') }}
              </button>
            </div>

            <div class="msg" :class="[m.role.toLowerCase(), { rolled: m.rolledBack }]">
              <div class="msg-role">
                {{ m.role }}<template v-if="m.turn != null"> · {{ turnLabel(m.turn) }}</template>
                <span v-if="m.rolledBack" class="rolled-pill">{{ t('chat.rewind.rolledBackBadge') }}</span>
              </div>

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
          </template>

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

    <!-- ---------- 回滚面板（侧抽屉） ---------- -->
    <RewindPanel
      v-if="showRewind"
      :session-id="id"
      :running="isRunning"
      @close="showRewind = false"
      @done="onRewindDone"
    />

    <!-- ---------- 恢复确认（危险操作二次确认） ---------- -->
    <div v-if="showRecover" class="modal-backdrop" @click.self="!recoverBusy && (showRecover = false)">
      <div class="modal">
        <h2>{{ t('chat.rewind.recoverConfirmTitle') }}</h2>
        <p class="modal-text">{{ recoverConfirmText }}</p>
        <div v-if="recoverError" class="notice error">{{ recoverError }}</div>
        <div class="modal-actions">
          <button :disabled="recoverBusy" @click="showRecover = false">{{ t('cancel') }}</button>
          <button class="primary" :disabled="recoverBusy" @click="doRecover">
            {{ recoverBusy ? t('chat.rewind.recovering') : t('chat.rewind.recoverConfirmAction') }}
          </button>
        </div>
      </div>
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

/* ===== 会话回滚（rewind） ===== */
.rewind-entry {
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--text-dim);
}

.rewind-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 900px;
  margin: 6px 0 16px;
}
.rewind-divider-text {
  flex: 1;
  color: var(--text-faint);
  font-size: 11.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rewind-divider button {
  flex-shrink: 0;
  font-size: 12px;
}

/* 已回滚内容：灰显低对比，但照常可读可展开 */
.msg.rolled {
  opacity: 0.55;
}
.msg.rolled:hover {
  opacity: 0.85;
}
.rolled-pill {
  margin-left: 8px;
  padding: 1px 7px;
  border-radius: 20px;
  background: var(--bg-input);
  color: var(--text-faint);
  font-size: 10px;
  letter-spacing: 0;
  text-transform: none;
}

.modal-text {
  color: var(--text-dim);
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.65;
}
</style>
