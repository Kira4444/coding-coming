<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type { Skill } from '../types'
import { t } from '../i18n'

const props = defineProps<{ projectId: number }>()

const skills = ref<Skill[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)

const editing = ref<number | 'new' | null>(null)
const form = reactive({
  name: '',
  description: '',
  content: '',
  enabled: true,
})

const expanded = ref<Set<number>>(new Set())

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    skills.value = await api.listSkills(props.projectId)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function openNew() {
  Object.assign(form, { name: '', description: '', content: '', enabled: true })
  editing.value = 'new'
}

function openEdit(s: Skill) {
  Object.assign(form, {
    name: s.name,
    description: s.description ?? '',
    content: s.content,
    enabled: s.enabled,
  })
  editing.value = s.id
}

function toggle(id: number) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      content: form.content,
      enabled: form.enabled,
    }
    if (editing.value === 'new') {
      await api.createSkill(props.projectId, payload)
    } else if (typeof editing.value === 'number') {
      await api.updateSkill(props.projectId, editing.value, payload)
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
    await api.deleteSkill(props.projectId, id)
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function toggleEnabled(s: Skill) {
  try {
    await api.updateSkill(props.projectId, s.id, { enabled: !s.enabled })
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2>{{ t('skills') }}</h2>
      <button @click="openNew">＋ {{ t('addSkill') }}</button>
    </div>
    <p class="hint">{{ t('skillsHint') }}</p>

    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="loading" class="loading">{{ t('loading') }}</div>

    <div v-else-if="skills.length === 0 && editing === null" class="empty">
      {{ t('noSkills') }}
    </div>

    <div v-else-if="editing === null" class="skill-list">
      <div v-for="s in skills" :key="s.id" class="skill-row" :class="{ dim: !s.enabled }">
        <div class="skill-head" @click="toggle(s.id)">
          <span class="skill-name">{{ s.name }}</span>
          <span v-if="s.description" class="skill-desc">{{ s.description }}</span>
          <span class="badge" :class="s.enabled ? 'ok' : 'dim'">
            {{ s.enabled ? t('enabled') : t('disabled') }}
          </span>
          <span class="chevron">{{ expanded.has(s.id) ? '▾' : '▸' }}</span>
        </div>

        <div v-if="expanded.has(s.id)" class="skill-body">
          <pre class="skill-content">{{ s.content }}</pre>
          <div class="skill-actions">
            <button @click="toggleEnabled(s)">
              {{ s.enabled ? t('disable') : t('enable') }}
            </button>
            <button @click="openEdit(s)">{{ t('edit') }}</button>
            <button class="danger" @click="remove(s.id)">{{ t('delete') }}</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑 -->
    <div v-else class="skill-form">
      <div class="grid-2">
        <div class="field">
          <label>{{ t('skillName') }}</label>
          <input v-model="form.name" placeholder="frontend-guide" />
          <div class="field-hint">{{ t('skillNameHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('skillDescription') }}</label>
          <input v-model="form.description" placeholder="前端开发约定与常见坑" />
        </div>
      </div>

      <div class="field">
        <label>{{ t('skillContent') }}</label>
        <textarea v-model="form.content" rows="12" :placeholder="t('skillContentPlaceholder')"></textarea>
        <div class="field-hint">{{ t('skillContentHint') }}</div>
      </div>

      <label class="check-row">
        <input type="checkbox" v-model="form.enabled" />
        <span>{{ t('enabled') }}</span>
      </label>

      <div class="form-actions">
        <button @click="editing = null">{{ t('cancel') }}</button>
        <button class="primary" :disabled="busy || !form.name.trim() || !form.content" @click="save">
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

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.skill-row {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg);
  overflow: hidden;
}
.skill-row.dim {
  opacity: 0.58;
}
.skill-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 13px;
  cursor: pointer;
}
.skill-head:hover {
  background: var(--bg-input);
}
.skill-name {
  font-family: var(--mono);
  font-size: 12.5px;
  font-weight: 600;
}
.skill-desc {
  flex: 1;
  color: var(--text-faint);
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.chevron {
  color: var(--text-faint);
  font-size: 11px;
}
.skill-body {
  border-top: 1px solid var(--border);
  padding: 11px 13px;
}
.skill-content {
  max-height: 280px;
  overflow: auto;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 10px 12px;
  font-family: var(--mono);
  font-size: 11.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.skill-actions {
  display: flex;
  gap: 6px;
  margin-top: 10px;
}

.skill-form {
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
.field input {
  width: 100%;
}
.field textarea {
  width: 100%;
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.6;
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
</style>
