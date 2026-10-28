<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from '../api'
import type { RewindPoint, RewindPointsView } from '../types'
import { t, tf } from '../i18n'

/**
 * 回滚面板：列出可回滚点（每轮结束时的对话 + 代码快照），
 * 选一个点二次确认后 POST /rewind —— 对话与代码一起退回，可恢复。
 */
const props = defineProps<{
  sessionId: string
  /** SSE 流进行中：禁用一切回滚动作 */
  running: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'done', payload: { kind: 'REWIND' | 'RECOVER'; toTurn: number }): void
}>()

const view = ref<RewindPointsView | null>(null)
const loading = ref(true)
const error = ref('')
const confirmTarget = ref<RewindPoint | null>(null)
const busy = ref(false)
const confirmError = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    view.value = await api.listRewindPoints(props.sessionId)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

const blocked = computed(
  () => props.running || !!view.value?.running || !!view.value?.workspacePurged || busy.value,
)

function disabledTip(): string {
  if (view.value?.workspacePurged) return t('chat.rewind.tipPurged')
  return t('chat.rewind.tipRunning')
}

/** 面板头部展示的会话分支短 sha = 当前尖端轮次的检查点 sha */
const tipSha = computed(() => {
  const v = view.value
  if (!v) return ''
  return v.points.find((p) => p.turn === v.currentTurn)?.shortSha ?? ''
})

function turnLabel(turn: number): string {
  return turn === 0 ? t('chat.rewind.initialTitle') : tf('chat.rewind.turnLabel', { turn })
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const now = new Date()
  const sameDay =
    d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

/** 确认框里「撤销其后的 N 轮共 M 条消息」的两个数字，由 rewind-points 现算 */
function undoneTurns(target: number): number {
  return Math.max(0, (view.value?.currentTurn ?? 0) - target)
}

function undoneMessages(target: number): number {
  return (view.value?.points ?? [])
    .filter((p) => p.turn > target)
    .reduce((sum, p) => sum + p.messageCount, 0)
}

const confirmKeepText = computed(() => {
  const target = confirmTarget.value
  if (!target) return ''
  const turns = undoneTurns(target.turn)
  const messages = undoneMessages(target.turn)
  return target.turn === 0
    ? tf('chat.rewind.rewindConfirmKeepZero', { turns, messages })
    : tf('chat.rewind.rewindConfirmKeep', { to: target.turn, turns, messages })
})

const confirmCodeText = computed(() => {
  const target = confirmTarget.value
  if (!target) return ''
  return target.turn === 0
    ? t('chat.rewind.rewindConfirmCodeZero')
    : tf('chat.rewind.rewindConfirmCode', { to: target.turn })
})

function errorText(e: unknown): string {
  const msg = (e as Error).message
  return e instanceof ApiError && e.status === 409 ? `${msg}（${t('chat.rewind.refreshHint')}）` : msg
}

async function doRewind() {
  const target = confirmTarget.value
  if (!target || busy.value) return
  busy.value = true
  confirmError.value = ''
  try {
    const op = await api.rewind(props.sessionId, {
      turn: target.turn,
      // 并发防护：面板加载时的尖端，双击 / 多标签页下与服务端不符会被 409 拒绝
      expectedTipTurn: view.value?.currentTurn,
    })
    emit('done', { kind: 'REWIND', toTurn: op.toTurn })
    emit('close')
  } catch (e) {
    // 失败留在确认框里显示后端文案，关掉会让人以为没反应
    confirmError.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="rw-backdrop" @click.self="!busy && emit('close')">
    <aside class="rw-drawer">
      <header class="rw-head">
        <div class="rw-head-text">
          <h2>{{ t('chat.rewind.panelTitle') }}</h2>
          <div v-if="view" class="rw-tip">
            {{ t('chat.rewind.currentTip') }}：{{ turnLabel(view.currentTurn) }}
            <template v-if="tipSha"> · {{ tipSha }}</template>
          </div>
        </div>
        <button class="ghost" :disabled="busy" @click="emit('close')">✕</button>
      </header>

      <div class="rw-body">
        <div class="rw-hint">{{ t('chat.rewind.panelHint') }}</div>

        <div v-if="loading" class="loading">{{ t('loading') }}</div>
        <template v-else>
          <div v-if="error" class="notice error">{{ error }}</div>

          <div v-if="view && view.points.length === 0" class="notice info">
            {{ t('chat.rewind.noPoints') }}
            <div class="rw-sub">{{ t('chat.rewind.noPointsHint') }}</div>
          </div>

          <div
            v-for="p in view?.points ?? []"
            :key="p.turn"
            class="rw-row"
            :class="{ inactive: !p.active }"
          >
            <div class="rw-row-head">
              <span class="pill">{{ turnLabel(p.turn) }}</span>
              <span class="pill" :class="p.active ? 'ok' : 'dim'">
                {{ p.active ? t('chat.rewind.activeBadge') : t('chat.rewind.rolledBackBadge') }}
              </span>
              <span class="rw-meta">{{ formatTime(p.createdAt) }} · {{ p.shortSha }}</span>
            </div>

            <template v-if="p.turn === 0">
              <div class="rw-title">{{ t('chat.rewind.initialTitle') }}</div>
              <div class="rw-sub">{{ t('chat.rewind.initialHint') }}</div>
            </template>
            <template v-else>
              <div class="rw-title">{{ p.prompt ?? '—' }}</div>
              <div v-if="p.assistantSummary" class="rw-sub">{{ p.assistantSummary }}</div>
            </template>

            <div class="rw-row-foot">
              <span v-if="p.messageCount > 0" class="rw-meta">
                {{ tf('chat.rewind.messageCount', { count: p.messageCount }) }}
              </span>
              <button
                class="danger"
                :disabled="blocked"
                :title="blocked ? disabledTip() : ''"
                @click="confirmTarget = p"
              >
                {{ t('chat.rewind.rewindToHere') }}
              </button>
            </div>
          </div>
        </template>
      </div>
    </aside>
  </div>

  <!-- 二次确认：危险操作，讲清保留什么 / 代码一起回 / 可恢复 -->
  <div v-if="confirmTarget" class="modal-backdrop" @click.self="!busy && (confirmTarget = null)">
    <div class="modal">
      <h2>{{ t('chat.rewind.rewindConfirmTitle') }}</h2>
      <p class="modal-text">{{ confirmKeepText }}</p>
      <p class="modal-text">{{ t('chat.rewind.rewindConfirmUserInput') }}</p>
      <p class="modal-text">{{ confirmCodeText }}</p>
      <p class="modal-text">{{ t('chat.rewind.rewindConfirmSafe') }}</p>
      <div v-if="confirmError" class="notice error">{{ confirmError }}</div>
      <div class="modal-actions">
        <button :disabled="busy" @click="confirmTarget = null">{{ t('cancel') }}</button>
        <button class="danger" :disabled="busy" @click="doRewind">
          {{ busy ? t('chat.rewind.rewinding') : t('chat.rewind.rewindConfirmAction') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rw-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 40;
  display: flex;
  justify-content: flex-end;
}
.rw-drawer {
  width: min(480px, 100vw);
  height: 100%;
  background: var(--bg-elevated);
  border-left: 1px solid var(--border-strong);
  display: flex;
  flex-direction: column;
}
.rw-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--border);
}
.rw-head h2 {
  margin: 0;
  font-size: 16px;
}
.rw-tip {
  margin-top: 4px;
  color: var(--text-faint);
  font-size: 11.5px;
  font-family: var(--mono);
}
.ghost {
  background: transparent;
  border-color: transparent;
  color: var(--text-dim);
}
.ghost:hover:not(:disabled) {
  background: var(--bg-input);
}

.rw-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 18px 24px;
}
.rw-hint {
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.6;
  margin-bottom: 12px;
}
.loading {
  color: var(--text-faint);
}

.rw-row {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-input);
  padding: 11px 13px;
  margin-bottom: 10px;
}
.rw-row.inactive {
  opacity: 0.55;
}
.rw-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}
.pill {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 20px;
  background: var(--bg-elevated);
  color: var(--text-dim);
  flex-shrink: 0;
}
.pill.ok {
  background: var(--success-soft);
  color: var(--success);
}
.pill.dim {
  background: var(--bg-elevated);
  color: var(--text-faint);
}
.rw-meta {
  margin-left: auto;
  color: var(--text-faint);
  font-size: 11px;
  font-family: var(--mono);
  white-space: nowrap;
}
.rw-title {
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}
.rw-sub {
  margin-top: 3px;
  color: var(--text-dim);
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
}
.rw-row-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 9px;
}
.rw-row-foot .rw-meta {
  margin-left: 0;
}

.modal-text {
  color: var(--text-dim);
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.65;
}
</style>
