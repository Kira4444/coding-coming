<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DiffResponse, FileDiff } from '../types'

const props = defineProps<{
  diff: DiffResponse | null
  loading?: boolean
  error?: string
}>()

const expanded = ref<Set<string>>(new Set())

function toggle(path: string) {
  const next = new Set(expanded.value)
  if (next.has(path)) {
    next.delete(path)
  } else {
    next.add(path)
  }
  expanded.value = next
}

const stats = computed(() => {
  if (!props.diff) return null
  return {
    files: props.diff.files.length,
    added: props.diff.addedLines,
    removed: props.diff.removedLines,
  }
})

/**
 * 把 unified diff 的 hunk 文本拆成可渲染的行。
 *
 * <p>解析放在前端而不是后端拼好：diff 是只读数据，前端拆一次即可，
 * 后端保持纯 git 输出更易于与外部工具对齐。
 */
function parsePatch(patch: string): DiffLine[] {
  if (!patch) return []
  const lines = patch.split('\n')
  const result: DiffLine[] = []
  for (const line of lines) {
    if (line.startsWith('diff --git ') || line.startsWith('index ')
        || line.startsWith('--- ') || line.startsWith('+++ ')) {
      continue
    }
    if (line.startsWith('@@')) {
      result.push({ type: 'meta', text: line })
      continue
    }
    if (line.startsWith('+')) {
      result.push({ type: 'add', text: line.slice(1) })
      continue
    }
    if (line.startsWith('-')) {
      result.push({ type: 'del', text: line.slice(1) })
      continue
    }
    if (line.startsWith('\\')) {
      result.push({ type: 'meta', text: line })
      continue
    }
    result.push({ type: 'ctx', text: line.startsWith(' ') ? line.slice(1) : line })
  }
  return result
}

interface DiffLine {
  type: 'add' | 'del' | 'ctx' | 'meta'
  text: string
}

function changeLabel(f: FileDiff): string {
  switch (f.changeType) {
    case 'ADDED': return 'A'
    case 'DELETED': return 'D'
    case 'RENAMED': return 'R'
    default: return 'M'
  }
}

function changeClass(f: FileDiff): string {
  switch (f.changeType) {
    case 'ADDED': return 'add'
    case 'DELETED': return 'del'
    default: return 'mod'
  }
}
</script>

<template>
  <div>
    <div v-if="loading" class="diff-loading">加载中…</div>
    <div v-else-if="error" class="notice error">{{ error }}</div>

    <template v-else-if="diff && diff.files.length > 0">
      <div class="diff-stats">
        <span class="stat">{{ stats?.files }} {{ '个文件有改动' }}</span>
        <span class="stat add">+{{ stats?.added }}</span>
        <span class="stat del">-{{ stats?.removed }}</span>
      </div>

      <div class="file-list">
        <div v-for="f in diff.files" :key="f.path" class="file">
          <div class="file-head" @click="toggle(f.path)">
            <span class="badge" :class="changeClass(f)">{{ changeLabel(f) }}</span>
            <span class="path">{{ f.path }}</span>
            <span class="counts">
              <span class="add">+{{ f.added }}</span>
              <span class="del">-{{ f.removed }}</span>
            </span>
            <span class="chevron">{{ expanded.has(f.path) ? '▾' : '▸' }}</span>
          </div>

          <div v-if="expanded.has(f.path)" class="file-body">
            <table class="diff-table">
              <tbody>
                <tr v-for="(line, i) in parsePatch(f.patch)" :key="i" :class="line.type">
                  <td class="gutter">{{ line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' ' }}</td>
                  <td class="code">{{ line.text }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!f.patch" class="no-patch">（二进制文件或无 patch 内容）</div>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="empty">
      <div class="empty-title">暂无改动</div>
      <div class="empty-hint">agent 还没有修改任何文件。</div>
    </div>
  </div>
</template>

<style scoped>
.diff-loading {
  color: var(--text-faint);
  padding: 12px 0;
}

.diff-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-family: var(--mono);
  font-size: 12px;
}
.stat {
  color: var(--text-dim);
}
.stat.add {
  color: var(--success);
}
.stat.del {
  color: var(--danger);
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.file {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.file-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 12px;
  background: var(--bg-elevated);
  cursor: pointer;
  user-select: none;
}
.file-head:hover {
  background: var(--bg-input);
}
.badge {
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 600;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 4px;
  flex-shrink: 0;
}
.badge.add {
  background: var(--success-soft);
  color: var(--success);
}
.badge.del {
  background: var(--danger-soft);
  color: var(--danger);
}
.badge.mod {
  background: var(--warning-soft);
  color: var(--warning);
}
.path {
  font-family: var(--mono);
  font-size: 12.5px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.counts {
  display: flex;
  gap: 7px;
  font-family: var(--mono);
  font-size: 11px;
}
.counts .add {
  color: var(--success);
}
.counts .del {
  color: var(--danger);
}
.chevron {
  color: var(--text-faint);
  font-size: 11px;
}

.file-body {
  border-top: 1px solid var(--border);
  background: var(--bg);
  overflow-x: auto;
}
.diff-table {
  width: 100%;
  border-collapse: collapse;
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.55;
}
.diff-table td {
  padding: 0 10px 0 0;
  vertical-align: top;
  white-space: pre-wrap;
  word-break: break-word;
}
.gutter {
  width: 26px;
  text-align: center;
  color: var(--text-faint);
  user-select: none;
  padding-left: 10px;
}
.code {
  width: 100%;
}
tr.add {
  background: var(--success-soft);
}
tr.add .gutter {
  color: var(--success);
}
tr.del {
  background: var(--danger-soft);
}
tr.del .gutter {
  color: var(--danger);
}
tr.meta {
  background: var(--bg-elevated);
  color: var(--text-faint);
}
tr.meta .code {
  font-size: 11px;
}
.no-patch {
  padding: 12px;
  color: var(--text-faint);
  font-size: 12px;
}

.empty {
  text-align: center;
  padding: 32px 16px;
  color: var(--text-faint);
}
.empty-title {
  font-size: 14px;
  color: var(--text-dim);
}
.empty-hint {
  margin-top: 5px;
  font-size: 12.5px;
}
</style>
