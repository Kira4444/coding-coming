<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import type { Project, Session } from '../types'
import { t } from '../i18n'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const sessions = ref<Session[]>([])
const projects = ref<Project[]>([])
const filterProject = ref<number | ''>('')
const loading = ref(true)
const error = ref('')

const projectName = computed(() => {
  const map = new Map(projects.value.map((p) => [p.id, p.name]))
  return (id: number) => map.get(id) ?? `#${id}`
})

const filtered = computed(() => {
  if (filterProject.value === '') return sessions.value
  return sessions.value.filter((s) => s.projectId === filterProject.value)
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, p] = await Promise.all([api.listSessions(), api.listProjects()])
    sessions.value = s
    projects.value = p
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function totalTokens(s: Session): number {
  return s.promptTokens + s.completionTokens
}

function formatTime(s: Session): string {
  // Session DTO 没回时间，用 id 里的时间段信息不够可靠；显示状态即可
  return s.status
}
</script>

<template>
  <div>
    <PageHeader :title="t('sessions')" :subtitle="t('noSessionsHint')">
      <template #actions>
        <select v-model="filterProject" class="filter">
          <option value="">{{ t('allProjects') }}</option>
          <option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
        <button @click="load">↻ {{ t('refresh') }}</button>
      </template>
    </PageHeader>

    <div class="page-body">
      <div v-if="error" class="notice error">{{ error }}</div>
      <div v-if="loading" class="loading">{{ t('loading') }}</div>

      <EmptyState
        v-else-if="filtered.length === 0"
        icon="💬"
        :title="t('noSessions')"
        :hint="t('noSessionsHint')"
      >
        <template #actions>
          <button class="primary" @click="router.push({ name: 'projects' })">
            {{ t('projects') }}
          </button>
        </template>
      </EmptyState>

      <div v-else class="list">
        <div
          v-for="s in filtered"
          :key="s.id"
          class="row"
          @click="router.push({ name: 'chat', params: { id: s.id } })"
        >
          <div class="row-icon">💬</div>
          <div class="row-main">
            <div class="row-title">{{ s.title }}</div>
            <div class="row-sub">
              {{ projectName(s.projectId) }} · {{ s.branchName }}
              <template v-if="s.workspacePurged"> · {{ t('workspacePurged') }}</template>
            </div>
          </div>
          <div class="row-meta">
            <span class="pill" :class="{ dim: s.workspacePurged }">{{ formatTime(s) }}</span>
            <span class="tokens">{{ totalTokens(s).toLocaleString() }} tokens</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-body {
  padding: 18px 28px 40px;
}
.loading {
  color: var(--text-faint);
}
.filter {
  min-width: 160px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 12px 15px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
}
.row:hover {
  border-color: var(--border-strong);
  background: var(--bg-input);
}
.row-icon {
  font-size: 18px;
  opacity: 0.75;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-title {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-sub {
  color: var(--text-faint);
  font-size: 11.5px;
  font-family: var(--mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-meta {
  display: flex;
  align-items: center;
  gap: 11px;
  flex-shrink: 0;
}
.tokens {
  color: var(--text-faint);
  font-size: 11.5px;
  font-family: var(--mono);
}
.pill {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 20px;
  background: var(--success-soft);
  color: var(--success);
}
.pill.dim {
  background: var(--bg-input);
  color: var(--text-faint);
}
</style>
