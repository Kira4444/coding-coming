<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type { GitInfo, PublishConfig, PublishResult, Session } from '../types'
import { t } from '../i18n'

const props = defineProps<{ projectId: number; sessions: Session[] }>()

const config = ref<PublishConfig | null>(null)
const gitInfo = ref<GitInfo | null>(null)
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const publishing = ref(false)
const savedTip = ref('')
const result = ref<PublishResult | null>(null)

const sessionId = ref('')
const form = reactive({
  publishBranch: '',
  publishRemote: 'origin',
  credentialType: 'TOKEN' as 'TOKEN' | 'SSH_KEY',
  username: 'git',
  credential: '',
})
/** 只有勾选了才回传 credential，否则后端视为不改 */
const replaceCredential = ref(false)

onMounted(load)

const canPublish = computed(
  () => !!sessionId.value && !!config.value?.publishBranch && config.value?.credentialConfigured,
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    config.value = await api.getPublishConfig(props.projectId)
    Object.assign(form, {
      publishBranch: config.value.publishBranch ?? '',
      publishRemote: config.value.publishRemote ?? 'origin',
      credentialType: config.value.credentialType ?? 'TOKEN',
      username: config.value.username ?? 'git',
      credential: '',
    })
    replaceCredential.value = false
    if (props.sessions.length > 0 && !sessionId.value) {
      sessionId.value = props.sessions[0].id
    }
    await loadGitInfo()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function loadGitInfo() {
  if (!sessionId.value) { gitInfo.value = null; return }
  try {
    gitInfo.value = await api.getGitInfo(props.projectId, sessionId.value)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  savedTip.value = ''
  try {
    config.value = await api.savePublishConfig(props.projectId, {
      publishBranch: form.publishBranch.trim(),
      publishRemote: form.publishRemote.trim() || 'origin',
      credentialType: form.credentialType,
      username: form.username.trim(),
      ...(replaceCredential.value ? { credential: form.credential } : {}),
    })
    replaceCredential.value = false
    form.credential = ''
    savedTip.value = t('saved')
    setTimeout(() => (savedTip.value = ''), 2200)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function doPublish() {
  if (publishing.value || !sessionId.value) return
  publishing.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await api.publish(props.projectId, sessionId.value)
    await loadGitInfo()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <section class="panel">
    <h2>{{ t('gitAndPublish') }}</h2>
    <p class="hint">{{ t('publishHint') }}</p>

    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="savedTip" class="notice info">{{ savedTip }}</div>
    <div v-if="loading" class="loading">{{ t('loading') }}</div>

    <template v-else>
      <!-- 当前 git 状态 -->
      <div class="git-status">
        <div class="row">
          <span class="label">{{ t('currentBranch') }}</span>
          <span class="value mono">{{ gitInfo?.currentBranch ?? '-' }}</span>
        </div>
        <div class="row">
          <span class="label">{{ t('remoteUrl') }}</span>
          <span class="value mono">{{ gitInfo?.remoteUrl ?? '-' }}</span>
        </div>
        <div v-if="gitInfo && gitInfo.commitsAhead >= 0" class="row">
          <span class="label">{{ t('commitsAhead') }}</span>
          <span class="value mono">{{ gitInfo.commitsAhead }}</span>
        </div>
      </div>

      <div v-if="gitInfo && gitInfo.recentCommits.length" class="commits">
        <div class="label">{{ t('recentCommits') }}</div>
        <div v-for="c in gitInfo.recentCommits.slice(0, 8)" :key="c.hash" class="commit">
          <span class="mono short">{{ c.shortHash }}</span>
          <span class="subject">{{ c.subject }}</span>
          <span class="meta">{{ c.date }}</span>
        </div>
      </div>

      <!-- 发布配置 -->
      <div class="grid-2">
        <div class="field">
          <label>{{ t('publishBranch') }}</label>
          <input v-model="form.publishBranch" placeholder="release/2026-10" />
          <div class="field-hint">{{ t('publishBranchHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('publishRemote') }}</label>
          <input v-model="form.publishRemote" placeholder="origin" />
        </div>
      </div>

      <div class="grid-2">
        <div class="field">
          <label>{{ t('credentialType') }}</label>
          <select v-model="form.credentialType">
            <option value="TOKEN">TOKEN</option>
            <option value="SSH_KEY">SSH_KEY</option>
          </select>
        </div>
        <div v-if="form.credentialType === 'TOKEN'" class="field">
          <label>{{ t('credentialUsername') }}</label>
          <input v-model="form.username" placeholder="git" />
        </div>
      </div>

      <div class="field">
        <label>{{ t('credential') }}</label>
        <div v-if="config?.credentialConfigured" class="cred-state">
          <span class="pill ok">{{ t('credentialConfigured') }} {{ config.credentialMasked }}</span>
          <label class="check-row">
            <input type="checkbox" v-model="replaceCredential" />
            <span>{{ t('replaceCredential') }}</span>
          </label>
        </div>
        <textarea
          v-if="replaceCredential || !config?.credentialConfigured"
          v-model="form.credential"
          rows="3"
          :placeholder="form.credentialType === 'SSH_KEY' ? t('sshKeyPlaceholder') : t('tokenPlaceholder')"
        ></textarea>
        <div class="field-hint">{{ t('credentialHint') }}</div>
      </div>

      <div class="panel-actions">
        <button :disabled="busy" @click="save">{{ t('save') }}</button>
      </div>

      <!-- 发布动作 -->
      <div class="publish-box">
        <div class="field">
          <label>{{ t('publishFromSession') }}</label>
          <select v-model="sessionId" @change="loadGitInfo">
            <option v-for="s in props.sessions" :key="s.id" :value="s.id">
              {{ s.title }} ({{ s.branchName }})
            </option>
          </select>
        </div>

        <button
          class="primary"
          :disabled="publishing || !canPublish"
          @click="doPublish"
        >
          {{ publishing ? t('publishing') : t('publish') }}
        </button>

        <div v-if="!canPublish" class="field-hint">
          {{ !config?.publishBranch ? t('needPublishBranch') : t('needCredential') }}
        </div>

        <div v-if="result" class="notice" :class="result.ok ? 'info' : 'error'">
          <div>{{ result.message }}</div>
          <pre v-if="result.details" class="details">{{ result.details }}</pre>
        </div>
      </div>
    </template>
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
h2 {
  margin: 0 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: var(--text-faint);
}
.hint {
  margin: 0 0 14px;
  color: var(--text-faint);
  font-size: 12px;
  line-height: 1.6;
}
.loading {
  color: var(--text-faint);
}
.git-status {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 11px 13px;
  margin-bottom: 12px;
}
.row {
  display: flex;
  gap: 12px;
  padding: 3px 0;
  font-size: 12.5px;
}
.row .label {
  color: var(--text-faint);
  min-width: 110px;
  flex-shrink: 0;
}
.commits {
  margin-bottom: 14px;
}
.commits .label {
  font-size: 11px;
  color: var(--text-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}
.commit {
  display: flex;
  gap: 10px;
  align-items: baseline;
  padding: 3px 0;
  font-size: 12px;
}
.commit .short {
  color: var(--accent);
  flex-shrink: 0;
}
.commit .subject {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.commit .meta {
  color: var(--text-faint);
  font-size: 11px;
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
  font-size: 11.5px;
}
.field-hint {
  margin-top: 5px;
  color: var(--text-faint);
  font-size: 11px;
  line-height: 1.6;
}
.cred-state {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.pill {
  font-size: 11px;
  padding: 3px 9px;
  border-radius: 20px;
  font-family: var(--mono);
}
.pill.ok {
  background: var(--success-soft);
  color: var(--success);
}
.check-row {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 12px;
}
.panel-actions {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
.publish-box {
  border-top: 1px solid var(--border);
  padding-top: 14px;
  margin-top: 6px;
}
.details {
  margin-top: 8px;
  max-height: 220px;
  overflow: auto;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 8px 10px;
  font-family: var(--mono);
  font-size: 11px;
  white-space: pre-wrap;
}
.mono {
  font-family: var(--mono);
}
</style>
