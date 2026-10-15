<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import type { Project } from '../types'
import { t } from '../i18n'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const route = useRoute()
const projects = ref<Project[]>([])
const loading = ref(true)
const error = ref('')
const infoTip = ref('')

const showCreate = ref(false)
const busy = ref(false)
const form = ref({
  name: '',
  repoType: 'LOCAL_PATH' as 'GIT_URL' | 'LOCAL_PATH',
  repoUrl: '',
  localPath: '',
})

onMounted(() => {
  // 从项目详情页删除成功后跳过来，带 ?deleted=1 给一句确认
  if (route.query.deleted) {
    infoTip.value = t('deleteSuccess')
    setTimeout(() => (infoTip.value = ''), 2600)
  }
  load()
})

/** 代码来源必须填了才能建——空路径会在建会话时才报错，那太晚了 */
const sourceFilled = computed(() =>
  form.value.repoType === 'GIT_URL'
    ? form.value.repoUrl.trim().length > 0
    : form.value.localPath.trim().length > 0,
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    projects.value = await api.listProjects()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!form.value.name.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const p = await api.createProject({
      name: form.value.name.trim(),
      repoType: form.value.repoType,
      repoUrl: form.value.repoUrl || undefined,
      localPath: form.value.localPath || undefined,
    })
    showCreate.value = false
    form.value = { name: '', repoType: 'LOCAL_PATH', repoUrl: '', localPath: '' }
    void router.push({ name: 'project-detail', params: { id: String(p.id) } })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function sourceLabel(p: Project): string {
  return p.repoType === 'GIT_URL' ? (p.repoUrl ?? '') : (p.localPath ?? '')
}
</script>

<template>
  <div>
    <PageHeader :title="t('projects')" :subtitle="t('noProjectsHint')">
      <template #actions>
        <button class="primary" @click="showCreate = true">＋ {{ t('newProject') }}</button>
      </template>
    </PageHeader>

    <div class="page-body">
      <div v-if="error" class="notice error">{{ error }}</div>
      <div v-if="infoTip" class="notice info">{{ infoTip }}</div>

      <div v-if="loading" class="loading">{{ t('loading') }}</div>

      <EmptyState
        v-else-if="projects.length === 0"
        icon="📁"
        :title="t('noProjects')"
        :hint="t('noProjectsHint')"
      >
        <template #actions>
          <button class="primary" @click="showCreate = true">＋ {{ t('newProject') }}</button>
        </template>
      </EmptyState>

      <div v-else class="card-list">
        <div
          v-for="p in projects"
          :key="p.id"
          class="card"
          @click="router.push({ name: 'project-detail', params: { id: String(p.id) } })"
        >
          <div class="card-top">
            <div class="card-title">{{ p.name }}</div>
            <span class="badge">{{ p.repoType }}</span>
          </div>
          <div class="card-sub">{{ sourceLabel(p) }}</div>
          <div class="card-foot">
            <span v-if="p.sandboxImage" class="pill">{{ p.sandboxImage }}</span>
            <span v-if="p.modelOverride" class="pill">{{ p.modelOverride }}</span>
            <span class="card-open">{{ t('open') }} →</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建项目 -->
    <div v-if="showCreate" class="modal-backdrop" @click.self="showCreate = false">
      <div class="modal">
        <h2>{{ t('newProject') }}</h2>

        <div class="field">
          <label>{{ t('projectName') }}</label>
          <input v-model="form.name" placeholder="my-service" @keydown.enter="create" />
        </div>

        <div class="field">
          <label>{{ t('repoType') }}</label>
          <select v-model="form.repoType">
            <option value="LOCAL_PATH">{{ t('localPath') }}</option>
            <option value="GIT_URL">{{ t('repoUrl') }}</option>
          </select>
        </div>

        <div v-if="form.repoType === 'GIT_URL'" class="field">
          <label>{{ t('repoUrl') }}</label>
          <input v-model="form.repoUrl" placeholder="https://git.example.com/team/repo.git" />
          <div class="field-hint">{{ t('repoUrlHint') }}</div>
        </div>
        <div v-else class="field">
          <label>{{ t('localPath') }}</label>
          <input v-model="form.localPath" placeholder="C:/Users/you/code/my-service" />
          <div class="field-hint">{{ t('localPathHint') }}</div>
        </div>

        <div class="modal-actions">
          <button @click="showCreate = false">{{ t('cancel') }}</button>
          <button
            class="primary"
            :disabled="!form.name.trim() || !sourceFilled || busy"
            @click="create"
          >
            {{ t('create') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-body {
  padding: 22px 28px;
}
.loading {
  color: var(--text-faint);
  padding: 24px 0;
}

.card-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.card {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 15px 16px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.card:hover {
  border-color: var(--border-strong);
  background: var(--bg-input);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-title {
  font-weight: 600;
  font-size: 14.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 20px;
  background: var(--bg-input);
  color: var(--text-dim);
  flex-shrink: 0;
}
.card-sub {
  margin-top: 6px;
  color: var(--text-faint);
  font-size: 12px;
  font-family: var(--mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-foot {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.pill {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 20px;
  background: var(--accent-soft);
  color: var(--accent);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 130px;
}
.card-open {
  margin-left: auto;
  color: var(--text-faint);
  font-size: 11.5px;
}
</style>
