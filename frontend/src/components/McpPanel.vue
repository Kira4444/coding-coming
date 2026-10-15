<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type { McpServer } from '../types'
import { t } from '../i18n'

const props = defineProps<{ projectId: number }>()

const servers = ref<McpServer[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const checking = ref<number | null>(null)

const editing = ref<number | 'new' | null>(null)
const form = reactive({
  name: '',
  transport: 'STDIO' as 'STDIO' | 'SSE' | 'HTTP',
  command: '',
  args: '',
  env: '',
  url: '',
  enabled: true,
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    servers.value = await api.listMcpServers(props.projectId)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openNew() {
  Object.assign(form, {
    name: '', transport: 'STDIO', command: '', args: '', env: '', url: '', enabled: true,
  })
  editing.value = 'new'
}

function openEdit(s: McpServer) {
  Object.assign(form, {
    name: s.name,
    transport: s.transport,
    command: s.command ?? '',
    args: s.args ?? '',
    env: s.env ?? '',
    url: s.url ?? '',
    enabled: s.enabled,
  })
  editing.value = s.id
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const payload = {
      name: form.name.trim(),
      transport: form.transport,
      command: form.transport === 'STDIO' ? form.command : null,
      args: form.args || null,
      env: form.env || null,
      url: form.transport === 'STDIO' ? null : form.url,
      enabled: form.enabled,
    }
    if (editing.value === 'new') {
      await api.createMcpServer(props.projectId, payload)
    } else if (typeof editing.value === 'number') {
      await api.updateMcpServer(props.projectId, editing.value, payload)
    }
    editing.value = null
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function remove(id: number) {
  try {
    await api.deleteMcpServer(props.projectId, id)
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function check(s: McpServer) {
  checking.value = s.id
  try {
    await api.checkMcpServer(props.projectId, s.id)
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    checking.value = null
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2>{{ t('mcpServers') }}</h2>
      <button @click="openNew">＋ {{ t('addMcp') }}</button>
    </div>
    <p class="hint">{{ t('mcpHint') }}</p>

    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="loading" class="loading">{{ t('loading') }}</div>

    <div v-else-if="servers.length === 0 && editing === null" class="empty">
      {{ t('noMcp') }}
    </div>

    <!-- 列表 -->
    <div v-else-if="editing === null" class="mcp-list">
      <div v-for="s in servers" :key="s.id" class="mcp-row">
        <div class="mcp-main">
          <div class="mcp-head">
            <span class="mcp-name">{{ s.name }}</span>
            <span class="badge">{{ s.transport }}</span>
            <span class="badge" :class="s.enabled ? 'ok' : 'dim'">
              {{ s.enabled ? t('enabled') : t('disabled') }}
            </span>
            <span
              v-if="s.lastCheckOk != null"
              class="badge"
              :class="s.lastCheckOk ? 'ok' : 'err'"
              :title="s.lastCheckMsg ?? ''"
            >
              {{ s.lastCheckOk ? t('mcpOk') : t('mcpFail') }}
            </span>
          </div>
          <div class="mcp-cmd mono">
            {{ s.transport === 'STDIO' ? s.command : s.url }}
            <template v-if="s.args"> {{ s.args.replace(/[\n\r]+/g, ' ') }}</template>
          </div>
          <div v-if="s.lastCheckMsg" class="mcp-msg">{{ s.lastCheckMsg }}</div>
        </div>
        <div class="mcp-actions">
          <button :disabled="checking === s.id" @click="check(s)">
            {{ checking === s.id ? t('checking') : t('testConnection') }}
          </button>
          <button @click="openEdit(s)">{{ t('edit') }}</button>
          <button class="danger" @click="remove(s.id)">{{ t('delete') }}</button>
        </div>
      </div>
    </div>

    <!-- 编辑表单 -->
    <div v-else class="mcp-form">
      <div class="grid-2">
        <div class="field">
          <label>{{ t('mcpName') }}</label>
          <input v-model="form.name" placeholder="filesystem" />
          <div class="field-hint">{{ t('mcpNameHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('transport') }}</label>
          <select v-model="form.transport">
            <option value="STDIO">STDIO</option>
            <option value="SSE">SSE</option>
            <option value="HTTP">HTTP</option>
          </select>
        </div>
      </div>

      <template v-if="form.transport === 'STDIO'">
        <div class="field">
          <label>{{ t('mcpCommand') }}</label>
          <input v-model="form.command" placeholder="npx" />
        </div>
        <div class="field">
          <label>{{ t('mcpArgs') }}</label>
          <textarea v-model="form.args" rows="3" placeholder='["-y", "@modelcontextprotocol/server-filesystem", "/data"]'></textarea>
          <div class="field-hint">{{ t('mcpArgsHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('mcpEnv') }}</label>
          <textarea v-model="form.env" rows="3" placeholder="API_KEY=abc123"></textarea>
          <div class="field-hint">{{ t('mcpEnvHint') }}</div>
        </div>
      </template>
      <div v-else class="field">
        <label>{{ t('mcpUrl') }}</label>
        <input v-model="form.url" placeholder="http://localhost:3000/sse" />
        <div class="field-hint">{{ t('mcpTransportHint') }}</div>
      </div>

      <label class="check-row">
        <input type="checkbox" v-model="form.enabled" />
        <span>{{ t('enabled') }}</span>
      </label>

      <div class="form-actions">
        <button @click="editing = null">{{ t('cancel') }}</button>
        <button class="primary" :disabled="busy || !form.name.trim()" @click="save">
          {{ t('save') }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 18px 20px;
  margin-bottom: 16px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
h2 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: var(--text-faint);
}
.hint {
  margin: 8px 0 14px;
  color: var(--text-faint);
  font-size: 12px;
  line-height: 1.6;
}
.loading, .empty {
  color: var(--text-faint);
  font-size: 13px;
  padding: 10px 0;
}

.mcp-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mcp-row {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.mcp-main {
  flex: 1;
  min-width: 0;
}
.mcp-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 5px;
}
.mcp-name {
  font-weight: 600;
  font-family: var(--mono);
  font-size: 13px;
}
.badge {
  font-size: 10px;
  padding: 2px 7px;
  border-radius: 20px;
  background: var(--bg-input);
  color: var(--text-dim);
}
.badge.ok {
  background: var(--success-soft);
  color: var(--success);
}
.badge.err {
  background: var(--danger-soft);
  color: var(--danger);
}
.badge.dim {
  opacity: 0.6;
}
.mcp-cmd {
  color: var(--text-dim);
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mcp-msg {
  margin-top: 5px;
  color: var(--text-faint);
  font-size: 11px;
}
.mcp-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  align-items: flex-start;
}

.mcp-form {
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  padding: 15px;
  background: var(--bg);
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.field {
  margin-bottom: 11px;
}
.field label {
  display: block;
  margin-bottom: 5px;
  font-size: 12.5px;
  color: var(--text-dim);
}
.field input,
.field select {
  width: 100%;
}
.field textarea {
  width: 100%;
  font-family: var(--mono);
  font-size: 12px;
}
.field-hint {
  margin-top: 5px;
  color: var(--text-faint);
  font-size: 11px;
  line-height: 1.6;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 13px;
  cursor: pointer;
  font-size: 13px;
}
.form-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.mono {
  font-family: var(--mono);
}
</style>
