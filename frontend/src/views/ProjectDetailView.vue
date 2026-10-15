<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import type { Project, Session } from '../types'
import { KNOWN_TOOLS } from '../types'
import { t } from '../i18n'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import McpPanel from '../components/McpPanel.vue'
import SkillPanel from '../components/SkillPanel.vue'
import PublishPanel from '../components/PublishPanel.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()

const project = ref<Project | null>(null)
const sessions = ref<Session[]>([])
const loading = ref(true)
const error = ref('')
const savedTip = ref('')
const busy = ref(false)
const showDelete = ref(false)
const deleting = ref(false)
const deleteError = ref('')

const form = reactive({
  name: '',
  repoType: 'LOCAL_PATH' as 'GIT_URL' | 'LOCAL_PATH',
  repoUrl: '',
  localPath: '',
  sandboxImage: '',
  modelOverride: '',
  // agent 配置
  instructions: '',
  enabledTools: [] as string[],
  approvalPolicy: '' as string,
  maxTurns: '' as string,
  tokenBudget: '' as string,
  excludePaths: '',
})

watch(
  () => props.id,
  () => void load(),
  { immediate: false },
)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const idNum = Number(props.id)
    project.value = await api.getProject(idNum)
    const agent = project.value.agent ?? {
      instructions: null, enabledTools: [], approvalPolicy: null,
      maxTurns: null, tokenBudget: null, excludePaths: [],
    }
    Object.assign(form, {
      name: project.value.name,
      repoType: project.value.repoType,
      repoUrl: project.value.repoUrl ?? '',
      localPath: project.value.localPath ?? '',
      sandboxImage: project.value.sandboxImage ?? '',
      modelOverride: project.value.modelOverride ?? '',
      instructions: agent.instructions ?? '',
      enabledTools: [...(agent.enabledTools ?? [])],
      approvalPolicy: agent.approvalPolicy ?? '',
      maxTurns: agent.maxTurns != null ? String(agent.maxTurns) : '',
      tokenBudget: agent.tokenBudget != null ? String(agent.tokenBudget) : '',
      excludePaths: (agent.excludePaths ?? []).join('\n'),
    })
    sessions.value = await api.listSessions(idNum)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function toList(raw: string): string[] {
  return raw.split(/[,，\n\r]+/).map((s) => s.trim()).filter(Boolean)
}

function riskLabel(risk: '只读' | '写' | '高危'): string {
  switch (risk) {
    case '只读': return t('riskReadOnly')
    case '写': return t('riskWrite')
    default: return t('riskHigh')
  }
}

function riskClass(risk: '只读' | '写' | '高危'): string {
  switch (risk) {
    case '只读': return 'risk-low'
    case '写': return 'risk-mid'
    default: return 'risk-high'
  }
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  savedTip.value = ''
  try {
    project.value = await api.updateProject(Number(props.id), {
      name: form.name.trim(),
      repoType: form.repoType,
      repoUrl: form.repoUrl,
      localPath: form.localPath,
      sandboxImage: form.sandboxImage,
      modelOverride: form.modelOverride,
      agent: {
        instructions: form.instructions.trim() || null,
        enabledTools: [...form.enabledTools],
        approvalPolicy: form.approvalPolicy || null,
        maxTurns: form.maxTurns ? Number(form.maxTurns) : null,
        tokenBudget: form.tokenBudget ? Number(form.tokenBudget) : null,
        excludePaths: toList(form.excludePaths),
      },
    })
    savedTip.value = t('saved')
    setTimeout(() => (savedTip.value = ''), 2200)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function newSession() {
  try {
    const s = await api.createSession({ projectId: Number(props.id), approvalPolicy: 'ASK_ON_WRITE' })
    void router.push({ name: 'chat', params: { id: s.id } })
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function remove() {
  if (deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api.deleteProject(Number(props.id))
    // 跳回列表并带提示，用户能确认「删掉了」而不是盯着空弹窗猜
    void router.replace({ name: 'projects', query: { deleted: '1' } })
  } catch (e) {
    // 失败时弹窗留着、错误显示在里面——关掉弹窗会让人以为没反应
    deleteError.value = (e as Error).message
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader :title="project?.name ?? t('loading')" :subtitle="project?.repoType">
      <template #actions>
        <button @click="router.push({ name: 'projects' })">← {{ t('back') }}</button>
        <button class="primary" @click="newSession">＋ {{ t('newSession') }}</button>
      </template>
    </PageHeader>

    <div class="page-body">
      <div v-if="loading" class="loading">{{ t('loading') }}</div>

      <template v-else>
        <div v-if="error" class="notice error">{{ error }}</div>
        <div v-if="savedTip" class="notice info">{{ savedTip }}</div>

        <!-- 基本信息 -->
        <section class="panel">
          <h2>{{ t('basicInfo') }}</h2>

          <div class="field">
            <label>{{ t('projectName') }}</label>
            <input v-model="form.name" />
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
            <input v-model="form.repoUrl" />
          </div>
          <div v-else class="field">
            <label>{{ t('localPath') }}</label>
            <input v-model="form.localPath" />
          </div>

          <div class="panel-actions">
            <button class="primary" :disabled="busy || !form.name.trim()" @click="save">
              {{ t('save') }}
            </button>
          </div>
        </section>

        <!-- 高级设置 -->
        <section class="panel">
          <h2>{{ t('advancedSettings') }}</h2>

          <div class="field">
            <label>{{ t('sandboxImage') }}</label>
            <input v-model="form.sandboxImage" placeholder="coding-agent/sandbox:latest" />
            <div class="field-hint">{{ t('sandboxImageHint') }}</div>
          </div>

          <div class="field">
            <label>{{ t('modelOverride') }}</label>
            <input v-model="form.modelOverride" placeholder="deepseek/deepseek-chat" />
            <div class="field-hint">{{ t('modelOverrideHint') }}</div>
          </div>

          <div class="panel-actions">
            <button class="primary" :disabled="busy" @click="save">{{ t('save') }}</button>
          </div>
        </section>

        <!-- Agent 配置 -->
        <section class="panel agent-panel">
          <h2>{{ t('agentConfig') }}</h2>

          <div class="field">
            <label>{{ t('agentInstructions') }}</label>
            <div class="field-hint">{{ t('agentInstructionsHint') }}</div>
            <textarea
              v-model="form.instructions"
              class="instructions"
              rows="8"
              :placeholder="t('agentInstructionsPlaceholder')"
            ></textarea>
          </div>

          <div class="field">
            <label>{{ t('toolsSection') }}</label>
            <div class="field-hint">{{ t('toolsSectionHint') }}</div>
            <div class="tool-list">
              <label v-for="tool in KNOWN_TOOLS" :key="tool.name" class="tool-row">
                <input
                  type="checkbox"
                  :value="tool.name"
                  v-model="form.enabledTools"
                />
                <span class="tool-name">{{ tool.label }}</span>
                <span class="tool-desc">{{ tool.desc }}</span>
                <span class="risk" :class="riskClass(tool.risk)">{{ riskLabel(tool.risk) }}</span>
              </label>
            </div>
          </div>

          <div class="grid-2">
            <div class="field">
              <label>{{ t('projectApprovalPolicy') }}</label>
              <select v-model="form.approvalPolicy">
                <option value="">{{ t('policyInherit') }}</option>
                <option value="ASK_ON_WRITE">{{ t('policyAskOnWrite') }}</option>
                <option value="AUTO">{{ t('policyAuto') }}</option>
                <option value="ALWAYS_ASK">{{ t('policyAlwaysAsk') }}</option>
              </select>
              <div class="field-hint">{{ t('projectApprovalPolicyHint') }}</div>
            </div>

            <div class="field">
              <label>{{ t('maxTurns') }}</label>
              <input v-model="form.maxTurns" type="number" min="1" placeholder="40" />
              <div class="field-hint">{{ t('maxTurnsHint') }}</div>
            </div>
          </div>

          <div class="field">
            <label>{{ t('tokenBudget') }}</label>
            <input v-model="form.tokenBudget" type="number" min="1" step="1000" placeholder="500000" />
            <div class="field-hint">{{ t('tokenBudgetHint') }}</div>
          </div>

          <div class="field">
            <label>{{ t('excludePaths') }}</label>
            <textarea
              v-model="form.excludePaths"
              rows="3"
              :placeholder="t('excludePathsPlaceholder')"
            ></textarea>
            <div class="field-hint">{{ t('excludePathsHint') }}</div>
          </div>

          <div class="panel-actions">
            <button class="primary" :disabled="busy" @click="save">{{ t('save') }}</button>
          </div>
        </section>

        <!-- 扩展能力：MCP 与 Skills -->
        <McpPanel v-if="project" :project-id="project.id" />
        <SkillPanel v-if="project" :project-id="project.id" />

        <!-- Git 与发布 -->
        <PublishPanel
          v-if="project"
          :project-id="project.id"
          :sessions="sessions"
        />

        <!-- 会话 -->
        <section class="panel">
          <h2>{{ t('sessions') }}</h2>

          <EmptyState
            v-if="sessions.length === 0"
            icon="💬"
            :title="t('noSessions')"
            :hint="t('noSessionsHint')"
          >
            <template #actions>
              <button class="primary" @click="newSession">＋ {{ t('newSession') }}</button>
            </template>
          </EmptyState>

          <div v-else class="session-list">
            <div
              v-for="s in sessions"
              :key="s.id"
              class="session-row"
              @click="router.push({ name: 'chat', params: { id: s.id } })"
            >
              <div class="session-main">
                <div class="session-title">{{ s.title }}</div>
                <div class="session-sub">
                  {{ s.branchName }}
                  <template v-if="s.workspacePurged"> · {{ t('workspacePurged') }}</template>
                </div>
              </div>
              <div class="session-meta">
                <span class="pill">{{ s.status }}</span>
                <span class="tokens">
                  {{ t('tokenUsage') }} {{ (s.promptTokens + s.completionTokens).toLocaleString() }}
                </span>
              </div>
            </div>
          </div>
        </section>

        <!-- 危险操作 -->
        <section class="panel danger">
          <h2>{{ t('dangerZone') }}</h2>
          <p class="danger-text">{{ t('deleteProjectHint') }}</p>
          <button class="danger" @click="showDelete = true">{{ t('deleteProject') }}</button>
        </section>
      </template>
    </div>

    <div v-if="showDelete" class="modal-backdrop" @click.self="!deleting && (showDelete = false)">
      <div class="modal">
        <h2>{{ t('delete') }}</h2>
        <p style="color: var(--text-dim); margin: 0 0 8px">{{ t('confirmDelete') }}</p>
        <div v-if="deleteError" class="notice error">{{ deleteError }}</div>
        <div class="modal-actions">
          <button :disabled="deleting" @click="showDelete = false">{{ t('cancel') }}</button>
          <button class="danger" :disabled="deleting" @click="remove">
            {{ deleting ? t('deleting') : t('delete') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-body {
  padding: 22px 28px 48px;
  max-width: 860px;
}
.loading {
  color: var(--text-faint);
}

.panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 18px 20px;
  margin-bottom: 16px;
}
.panel h2 {
  margin: 0 0 14px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--text-faint);
}
.panel-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.field {
  margin-bottom: 12px;
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
.field-hint {
  margin-top: 5px;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.6;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.session-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.session-row:hover {
  border-color: var(--border-strong);
}
.session-main {
  flex: 1;
  min-width: 0;
}
.session-title {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-sub {
  color: var(--text-faint);
  font-size: 11.5px;
  font-family: var(--mono);
}
.session-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.tokens {
  color: var(--text-faint);
  font-size: 11.5px;
  font-family: var(--mono);
}
.pill {
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 20px;
  background: var(--bg-elevated);
  color: var(--text-dim);
}

.panel.danger {
  border-color: rgba(242, 85, 90, 0.35);
}
.panel.danger h2 {
  color: var(--danger);
}
.danger-text {
  margin: 0 0 12px;
  color: var(--text-dim);
  font-size: 13px;
}

/* ===== Agent 配置 ===== */
.agent-panel {
  border-color: rgba(79, 140, 255, 0.28);
}
.instructions {
  width: 100%;
  min-height: 150px;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.65;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 5px;
  background: var(--bg);
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 9px;
  border-radius: 4px;
  cursor: pointer;
}
.tool-row:hover {
  background: var(--bg-input);
}
.tool-row input[type='checkbox'] {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}
.tool-name {
  font-size: 12.5px;
  font-weight: 500;
  min-width: 62px;
}
.tool-desc {
  flex: 1;
  color: var(--text-faint);
  font-size: 11.5px;
}
.risk {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 20px;
  flex-shrink: 0;
}
.risk-low {
  background: var(--success-soft);
  color: var(--success);
}
.risk-mid {
  background: var(--warning-soft);
  color: var(--warning);
}
.risk-high {
  background: var(--danger-soft);
  color: var(--danger);
}

textarea {
  width: 100%;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
